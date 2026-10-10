package br.com.fruteira.vendas;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Regra de negócio e persistência do TEF. O agente local fala com o pinpad; o front leva o resultado até aqui. */
@Path("/tef") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class TefResource {
    private static final Set<String> TIPOS = Set.of("CREDITO_A_VISTA", "CREDITO_PARCELADO", "DEBITO");
    private static final Set<String> FINAIS = Set.of("APROVADA", "NEGADA", "CANCELADA", "ERRO");
    @Inject Auditor auditor;
    @Inject @RestClient Clients.Catalogo catalogo;
    /** A NFC-e exige NCM válido em todos os itens: sem isso o cartão nem é cobrado */
    @ConfigProperty(name = "fruteira.ncm.obrigatorio-tef", defaultValue = "true") boolean ncmObrigatorio;

    public record Iniciar(BigDecimal valor, String tipo, Integer parcelas, String terminal, List<Long> produtoIds) {}
    public record Resultado(String status, String mensagem, String nsu, String nsuHost, String autorizacao, String bandeira, String adquirente,
                            String cnpjCredenciadora, String cartao, String comprovanteCliente, String comprovanteLoja) {}
    public record Pendencia(Long id, String requisicao, String status, BigDecimal valor, Long vendaId, LocalDateTime criadoEm) {}

    /** Cria a transação PENDENTE e o número único que vai ao agente. */
    @POST @Path("/iniciar") @Transactional
    public TefTransacao iniciar(Iniciar i) {
        if (i.valor() == null || i.valor().signum() <= 0) throw Http.erro(422, "Informe um valor maior que zero");
        if (!TIPOS.contains(i.tipo())) throw Http.erro(422, "Tipo de transação inválido");
        int parcelas = i.parcelas() == null ? 1 : i.parcelas();
        if (parcelas < 1 || parcelas > 12 || ("CREDITO_PARCELADO".equals(i.tipo()) && parcelas < 2) || (!"CREDITO_PARCELADO".equals(i.tipo()) && parcelas != 1))
            throw Http.erro(422, "Número de parcelas inválido");
        if (ncmObrigatorio && i.produtoIds() != null && !i.produtoIds().isEmpty()) {
            List<String> problemas = new ArrayList<>();
            for (Long pid : new LinkedHashSet<>(i.produtoIds())) { Clients.ProdutoDTO p = catalogo.buscar(pid); String m = p.problemaNcm(); if (m != null) problemas.add(p.nome() + " (" + m + ")"); }
            if (!problemas.isEmpty()) throw Http.erro(422, "A NFC-e exige NCM válido e vigente em todos os itens. Corrija em Produtos e preços: " + String.join("; ", problemas));
        }
        TefTransacao t = new TefTransacao();
        t.valor = i.valor(); t.tipo = i.tipo(); t.parcelas = parcelas; t.terminal = i.terminal(); t.operador = auditor.usuario();
        t.persist();
        t.requisicao = "TEF" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now()) + String.format("%06d", t.id);
        return t;
    }

    /** Grava o retorno do TEF (NSU, autorização, bandeira, adquirente, comprovantes). Idempotente. */
    @POST @Path("/{id}/resultado") @Transactional
    public TefTransacao resultado(@PathParam("id") Long id, Resultado r) {
        TefTransacao t = achar(id);
        if (r == null || !FINAIS.contains(r.status())) throw Http.erro(422, "Status inválido");
        if (!"PENDENTE".equals(t.status)) {
            if (r.status().equals(t.status)) return t;
            throw Http.erro(409, "Transação já finalizada como " + t.status);
        }
        t.status = r.status(); t.mensagem = corta(r.mensagem(), 500);
        t.nsu = r.nsu(); t.nsuHost = r.nsuHost(); t.autorizacao = r.autorizacao(); t.bandeira = r.bandeira(); t.adquirente = r.adquirente();
        t.cnpjCredenciadora = r.cnpjCredenciadora(); t.cartao = r.cartao();
        t.comprovanteCliente = corta(r.comprovanteCliente(), 4000); t.comprovanteLoja = corta(r.comprovanteLoja(), 4000);
        t.atualizadoEm = LocalDateTime.now();
        return t;
    }

    /** Venda gravada e comprovante impresso: confirma. */
    @POST @Path("/{id}/confirmar") @Transactional
    public TefTransacao confirmar(@PathParam("id") Long id) {
        TefTransacao t = achar(id);
        if ("CONFIRMADA".equals(t.status)) return t;
        if (!"APROVADA".equals(t.status)) throw Http.erro(409, "Só transação aprovada pode ser confirmada (status " + t.status + ")");
        if (t.vendaId == null) throw Http.erro(409, "A transação ainda não está vinculada a uma venda");
        t.status = "CONFIRMADA"; t.confirmadoEm = LocalDateTime.now(); t.atualizadoEm = t.confirmadoEm;
        return t;
    }

    /** Venda não concluída (ou queda): desfaz a aprovação. Não vale para transação já vinculada a uma venda. */
    @POST @Path("/{id}/desfazer") @Transactional
    public TefTransacao desfazer(@PathParam("id") Long id) {
        TefTransacao t = achar(id);
        if ("DESFEITA".equals(t.status)) return t;
        if (!"APROVADA".equals(t.status)) throw Http.erro(409, "Só transação aprovada pode ser desfeita (status " + t.status + ")");
        if (t.vendaId != null) throw Http.erro(409, "Transação vinculada à venda #" + t.vendaId + ": não pode ser desfeita");
        t.status = "DESFEITA"; t.atualizadoEm = LocalDateTime.now();
        auditor.registrar("CANCELAMENTO", "Transação TEF", id, "Transação TEF " + t.requisicao + " desfeita (R$ " + t.valor + ", NSU " + t.nsu + ")", null, null);
        return t;
    }

    /** Estorno (cancelamento administrativo) de transação confirmada. Somente supervisor/gerente. */
    @POST @Path("/{id}/estornar") @Transactional
    public TefTransacao estornar(@PathParam("id") Long id, Resultado r) {
        if (!auditor.supervisorOuMais()) throw new ForbiddenException("Somente supervisor ou gerente pode estornar");
        TefTransacao t = achar(id);
        if ("ESTORNADA".equals(t.status)) return t;
        if (!"CONFIRMADA".equals(t.status)) throw Http.erro(409, "Só transação confirmada pode ser estornada (status " + t.status + ")");
        if (r == null || !"APROVADA".equals(r.status())) throw Http.erro(422, "O cancelamento não foi aprovado pelo TEF: " + (r == null ? "" : r.mensagem()));
        t.status = "ESTORNADA"; t.atualizadoEm = LocalDateTime.now();
        auditor.registrar("CANCELAMENTO", "Transação TEF", id, "Estorno do cartão: " + t.requisicao + " (R$ " + t.valor + ", NSU " + t.nsu + ", venda #" + t.vendaId + ")", null, null);
        return t;
    }

    /** Aprovadas sem confirmação/desfazimento e iniciadas há mais de 2 min sem resultado: o PDV reconcilia ao abrir. */
    @GET @Path("/pendencias")
    public List<Pendencia> pendencias() {
        List<TefTransacao> l = TefTransacao.list("status = ?1 or (status = ?2 and criadoEm < ?3)", "APROVADA", "PENDENTE", LocalDateTime.now().minusMinutes(2));
        return l.stream().map(t -> new Pendencia(t.id, t.requisicao, t.status, t.valor, t.vendaId, t.criadoEm)).toList();
    }
    @GET public List<TefTransacao> listar(@QueryParam("limit") @DefaultValue("100") int limit) {
        return TefTransacao.find("order by id desc").page(0, Math.min(Math.max(limit, 1), 500)).list();
    }
    @GET @Path("/{id}") public TefTransacao buscar(@PathParam("id") Long id) { return achar(id); }

    private TefTransacao achar(Long id) { TefTransacao t = TefTransacao.findById(id); if (t == null) throw Http.erro(404, "Transação TEF inexistente"); return t; }
    private static String corta(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
}
