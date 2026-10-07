package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Regras do Pix: cria a cobrança no PSP, recebe o webhook (idempotente), expira, cancela e devolve. */
@ApplicationScoped
public class PixService {
    private static final Logger LOG = Logger.getLogger(PixService.class);
    @Inject @Any Instance<PixProvider> providers;
    @Inject Auditor auditor;
    @ConfigProperty(name = "pix.provider", defaultValue = "simulador") String providerNome;
    @ConfigProperty(name = "pix.expiracao-seg", defaultValue = "300") int expiracaoSeg;

    public PixProvider provider() {
        for (PixProvider p : providers) if (p.nome().equalsIgnoreCase(providerNome)) return p;
        throw new IllegalStateException("Provedor Pix desconhecido: " + providerNome);
    }
    public Optional<SimuladorPixProvider> simulador() { PixProvider p = provider(); return p instanceof SimuladorPixProvider s ? Optional.of(s) : Optional.empty(); }

    @Transactional
    public PixCobranca criar(BigDecimal valor, String descricao, String terminal, String operador) {
        if (valor == null || valor.signum() <= 0) throw Http.erro(422, "Informe um valor maior que zero");
        String txid = UUID.randomUUID().toString().replace("-", "");            // 32 caracteres [a-zA-Z0-9] (Bacen: 26 a 35)
        PixProvider.Criada c;
        try { c = provider().criar(txid, valor, expiracaoSeg, descricao); }
        catch (Exception e) { LOG.error("Falha ao criar cobrança Pix", e); throw Http.erro(502, "Não foi possível gerar o Pix no PSP: " + e.getMessage()); }
        PixCobranca x = new PixCobranca();
        x.txid = txid; x.valor = valor; x.descricao = descricao; x.provedor = provider().nome();
        x.pixCopiaECola = c.pixCopiaECola(); x.location = c.location(); x.expiraEm = LocalDateTime.now().plusSeconds(expiracaoSeg);
        x.operador = operador; x.terminal = terminal;
        x.persist();
        return x;
    }

    /** Consulta do front (polling): expira o que venceu e, a cada 10 s, confere no PSP (rede de segurança se o webhook não chegar). */
    @Transactional
    public PixCobranca atualizar(Long id) {
        PixCobranca c = achar(id);
        if (!"ATIVA".equals(c.status)) return c;
        LocalDateTime agora = LocalDateTime.now();
        if (agora.isAfter(c.expiraEm)) { c.status = "EXPIRADA"; return c; }
        if (c.ultimaConsulta == null || c.ultimaConsulta.isBefore(agora.minusSeconds(10))) {
            c.ultimaConsulta = agora;
            try {
                PixProvider.Situacao s = provider().consultar(c.txid);
                if ("CONCLUIDA".equals(s.status()) && s.endToEndId() != null) aplicarPagamento(c, s.endToEndId(), s.valor() != null ? s.valor() : c.valor, s.horario());
                else if ("REMOVIDA".equals(s.status())) c.status = "CANCELADA";
            } catch (Exception e) { LOG.warn("Não foi possível conferir o Pix " + c.txid + " no PSP: " + e.getMessage()); }
        }
        return c;
    }

    /** Webhook do PSP (payload padrão do Bacen: {"pix":[{endToEndId, txid, valor, horario}]}). Retorna quantos pagamentos novos aplicou. */
    @Transactional
    public int receberNotificacao(JsonNode corpo) {
        int aplicados = 0;
        for (JsonNode p : corpo.path("pix")) {
            String txid = p.path("txid").asText(null), e2e = p.path("endToEndId").asText(null);
            if (txid == null || e2e == null) continue;
            PixCobranca c = PixCobranca.find("txid", txid).firstResult();
            if (c == null) { LOG.warnf("Webhook Pix de txid desconhecido: %s", txid); continue; }
            LocalDateTime quando = LocalDateTime.now();
            try { quando = OffsetDateTime.parse(p.path("horario").asText()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignorada) { /* usa agora */ }
            if (aplicarPagamento(c, e2e, new BigDecimal(p.path("valor").asText("0")), quando)) aplicados++;
        }
        return aplicados;
    }

    /** Idempotente: o mesmo endToEndId nunca é aplicado duas vezes. */
    private boolean aplicarPagamento(PixCobranca c, String e2e, BigDecimal valor, LocalDateTime quando) {
        if (PixCobranca.count("endToEndId", e2e) > 0) return false;
        if (List.of("CONCLUIDA", "DIVERGENTE", "DEVOLVIDA").contains(c.status)) { LOG.warnf("Segundo pagamento (%s) para a cobrança %s já paga: devolva manualmente", e2e, c.txid); return false; }
        c.endToEndId = e2e; c.valorPago = valor; c.pagoEm = quando;
        c.status = valor.compareTo(c.valor) == 0 ? "CONCLUIDA" : "DIVERGENTE";      // chegou depois de expirar/cancelar? o dinheiro entrou: vale como pago
        return true;
    }

    @Transactional
    public PixCobranca cancelar(Long id) {
        PixCobranca c = achar(id);
        if (!"ATIVA".equals(c.status)) return c;
        try { provider().cancelar(c.txid); } catch (Exception e) { LOG.warn("Não foi possível cancelar no PSP: " + e.getMessage()); }
        c.status = "CANCELADA";
        return c;
    }

    @Transactional
    public PixCobranca devolver(Long id) {
        PixCobranca c = achar(id);
        if ("DEVOLVIDA".equals(c.status)) return c;
        if (!"CONCLUIDA".equals(c.status) && !"DIVERGENTE".equals(c.status)) throw Http.erro(409, "Só Pix recebido pode ser devolvido (status " + c.status + ")");
        String idDev = ("D" + c.id + System.currentTimeMillis()).substring(0, Math.min(35, ("D" + c.id + System.currentTimeMillis()).length()));
        try { provider().devolver(c.endToEndId, idDev, c.valorPago); }
        catch (Exception e) { throw Http.erro(502, "O PSP não aceitou a devolução: " + e.getMessage()); }
        c.status = "DEVOLVIDA"; c.idDevolucao = idDev; c.devolvidaEm = LocalDateTime.now();
        auditor.registrar("CANCELAMENTO", "Pix", c.id, "Pix devolvido: R$ " + c.valorPago + " (E2E " + c.endToEndId + (c.vendaId != null ? ", venda #" + c.vendaId : "") + ")", null, null);
        return c;
    }

    /** Pix recebido que não virou venda (ex.: o caixa travou): aparece para devolução ou reaproveitamento. */
    @Transactional
    public List<PixCobranca> pendencias() {
        expirarVencidas();
        return PixCobranca.list("status in ?1 and vendaId is null and pagoEm < ?2", List.of("CONCLUIDA", "DIVERGENTE"), LocalDateTime.now().minusMinutes(2));
    }
    @Transactional
    public void expirarVencidas() { PixCobranca.update("status = ?1 where status = ?2 and expiraEm < ?3", "EXPIRADA", "ATIVA", LocalDateTime.now()); }

    public PixCobranca achar(Long id) { PixCobranca c = PixCobranca.findById(id); if (c == null) throw Http.erro(404, "Cobrança Pix inexistente"); return c; }
}
