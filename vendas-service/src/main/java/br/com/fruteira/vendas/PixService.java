package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.jboss.logging.Logger;

/** Regras do Pix: cria a cobrança no PSP, recebe o webhook (idempotente), expira, cancela e devolve. */
@ApplicationScoped
public class PixService {
    private static final Logger LOG = Logger.getLogger(PixService.class);
    @Inject @Any Instance<PixProviderService> servicos;
    @Inject PixConfig config;
    @Inject Auditor auditor;

    /** Provedor escolhido em PIX_PROVIDER (erro 500 com a lista de opções se o valor for inválido). */
    public PixProvider provedor() {
        try { return PixProvider.deTexto(config.provider()); } catch (IllegalArgumentException e) { throw Http.erro(500, e.getMessage()); }
    }
    /** Implementação do provedor escolhido (erro 501 se o provedor existe no enum mas ainda não tem integração). */
    public PixProviderService servico() {
        PixProvider p = provedor();
        List<String> disponiveis = new ArrayList<>();
        for (PixProviderService s : servicos) { if (s.provider() == p) return s; disponiveis.add(s.provider().name()); }
        Collections.sort(disponiveis);
        throw Http.erro(501, "O provedor Pix " + p.rotulo() + " (" + p.name() + ") ainda não tem integração neste sistema. Disponíveis: " + String.join(", ", disponiveis));
    }
    public Optional<SimuladorPixService> simulador() { return servico() instanceof SimuladorPixService s ? Optional.of(s) : Optional.empty(); }

    /** Na partida avisa no log o que falta configurar (não derruba o serviço: o resto do sistema segue funcionando). */
    void aoIniciar(@Observes StartupEvent ev) {
        try {
            PixProviderService s = servico();
            List<String> problemas = s.problemasDeConfiguracao();
            if (problemas.isEmpty()) LOG.infof("Pix: provedor %s (%s)", s.provider().name(), s.provider().rotulo());
            else LOG.warnf("Pix: provedor %s com configuração incompleta: %s", s.provider().name(), String.join("; ", problemas));
        } catch (Exception e) { LOG.warn("Pix: " + e.getMessage()); }
    }

    @Transactional
    public PixCobranca criar(BigDecimal valor, String descricao, String terminal, String operador) {
        if (valor == null || valor.signum() <= 0) throw Http.erro(422, "Informe um valor maior que zero");
        String txid = UUID.randomUUID().toString().replace("-", "");            // 32 caracteres [a-zA-Z0-9] (Bacen: 26 a 35)
        PixProviderService svc = servico();
        PixProviderService.Criada c;
        try { c = svc.criar(txid, valor, config.expiracaoSeg(), descricao); }
        catch (Exception e) { LOG.error("Falha ao criar cobrança Pix", e); throw Http.erro(502, "Não foi possível gerar o Pix no PSP: " + e.getMessage()); }
        PixCobranca x = new PixCobranca();
        x.txid = txid; x.valor = valor; x.descricao = descricao; x.provedor = svc.provider().name();
        x.pixCopiaECola = c.pixCopiaECola(); x.location = c.location(); x.expiraEm = LocalDateTime.now().plusSeconds(config.expiracaoSeg());
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
        if (agora.isAfter(c.expiraEm)) {                        // venceu: confere uma última vez (pagou no último segundo?) e, se não, cancela no provedor para não aceitar pagamento tardio
            try {
                PixProviderService.Situacao s = servico().consultar(c.txid);
                if (s.status() == PixStatus.CONCLUIDA && s.endToEndId() != null) { aplicarPagamento(c, s.endToEndId(), s.valor() != null ? s.valor() : c.valor, s.horario()); return c; }
                servico().cancelar(c.txid);
            } catch (Exception e) { LOG.warn("Não foi possível conferir/cancelar o Pix vencido " + c.txid + " no provedor: " + e.getMessage()); }
            c.status = PixStatus.EXPIRADA.name();
            return c;
        }
        if (c.ultimaConsulta == null || c.ultimaConsulta.isBefore(agora.minusSeconds(10))) {
            c.ultimaConsulta = agora;
            try {
                PixProviderService.Situacao s = servico().consultar(c.txid);
                switch (s.status()) {
                    case CONCLUIDA -> { if (s.endToEndId() != null) aplicarPagamento(c, s.endToEndId(), s.valor() != null ? s.valor() : c.valor, s.horario()); }
                    case REMOVIDA_PELO_USUARIO_RECEBEDOR -> c.status = "CANCELADA";
                    case EXPIRADA -> c.status = PixStatus.EXPIRADA.name();
                    case ATIVA -> { /* segue aguardando */ }
                }
            } catch (Exception e) { LOG.warn("Não foi possível conferir o Pix " + c.txid + " no PSP: " + e.getMessage()); }
        }
        return c;
    }

    /** Webhook do PSP: cada provedor interpreta o seu payload ({@link PixProviderService#interpretarWebhook}). Retorna quantos pagamentos novos aplicou. */
    @Transactional
    public int receberNotificacao(JsonNode corpo) {
        int aplicados = 0;
        for (PixProviderService.Notificacao n : servico().interpretarWebhook(corpo)) {
            PixCobranca c = PixCobranca.find("txid", n.txid()).firstResult();
            if (c == null) { LOG.warnf("Webhook Pix de txid desconhecido: %s", n.txid()); continue; }
            if (aplicarPagamento(c, n.endToEndId(), n.valor(), n.horario())) aplicados++;
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
        try { servico().cancelar(c.txid); } catch (Exception e) { LOG.warn("Não foi possível cancelar no PSP: " + e.getMessage()); }
        c.status = "CANCELADA";
        return c;
    }

    @Transactional
    public PixCobranca devolver(Long id) {
        PixCobranca c = achar(id);
        if ("DEVOLVIDA".equals(c.status)) return c;
        if (!"CONCLUIDA".equals(c.status) && !"DIVERGENTE".equals(c.status)) throw Http.erro(409, "Só Pix recebido pode ser devolvido (status " + c.status + ")");
        String idDev = ("D" + c.id + System.currentTimeMillis()).substring(0, Math.min(35, ("D" + c.id + System.currentTimeMillis()).length()));
        try { servico().devolver(c.endToEndId, idDev, c.valorPago); }
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
