package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Nota;
import br.com.fruteira.retaguarda.fiscal.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * NFC-e + Nota Fiscal Gaúcha (CPF do consumidor no grupo &lt;dest&gt;).
 * provider=simulador: gera chave/protocolo fictícios (testes). provider=sefaz: monta o XML, assina com o certificado A1,
 * transmite à SEFAZ-RS por mTLS e trata o retorno (100 = autorizada · rejeição = corrigir CPF no caixa · sem rede = contingência offline).
 */
@Path("/nfce") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class NfceResource {
    static final Logger LOG = Logger.getLogger("fiscal");
    static final ObjectMapper JSON = new ObjectMapper();
    @Inject Auditor auditor;
    @Inject FiscalConfig fiscal;

    public record ItemIn(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor) {}
    public record PagIn(String tPag, BigDecimal valor, String cnpjCredenciadora, String tBand, String cAut) {}
    public record Emitir(Long vendaId, BigDecimal valor, String cpf, String nome, boolean contingencia, String cartoes, List<ItemIn> itens, List<PagIn> pagamentos) {}
    public record CorrigirCpf(String cpf, String nome) {}
    public record Cancelar(String justificativa) {}

    @GET public List<Nota> listar() { return Nota.find("order by id desc").list(); }

    @GET @Path("/status")
    public Map<String, Object> status() {
        return Map.of("provider", fiscal.provider(), "real", fiscal.real(), "ambiente", fiscal.ambiente() == 1 ? "PRODUCAO" : "HOMOLOGACAO", "pendencias", fiscal.real() ? fiscal.pendencias() : List.of());
    }

    @GET @Path("/cpf/{cpf}/validar")
    public Map<String, Object> validarCpf(@PathParam("cpf") String cpf) {
        boolean ok = Cpf.valido(cpf); return Map.of("valido", ok, "cpf", Cpf.somenteDigitos(cpf), "formatado", ok ? Cpf.formatar(cpf) : "");
    }

    @GET @Path("/{id}/xml") @Produces(MediaType.APPLICATION_XML)
    public Response xml(@PathParam("id") Long id) {
        Nota n = achar(id); if (n.xml == null) throw new NotFoundException();
        return Response.ok(n.xml).header("Content-Disposition", "attachment; filename=\"" + n.chave + ".xml\"").build();
    }

    @POST @Path("/emitir")
    public synchronized Nota emitir(Emitir e) {
        String cpf; try { cpf = Cpf.normalizarOuNulo(e.cpf()); } catch (IllegalArgumentException ex) { throw Http.erro(422, "CPF inválido"); }
        Nota n = new Nota();
        n.vendaId = e.vendaId(); n.valor = e.valor(); n.cpf = cpf; n.nome = e.nome();
        n.cartoes = e.cartoes() != null && e.cartoes().length() > 2000 ? e.cartoes().substring(0, 2000) : e.cartoes();
        n.numero = proximoNumero();
        try { n.payload = JSON.writeValueAsString(e); } catch (Exception ex) { /* sem payload: só não reenvia */ }
        if (!fiscal.real()) { simular(n, e.contingencia()); return emitida(n); }
        if (e.itens() == null || e.itens().isEmpty() || e.pagamentos() == null || e.pagamentos().isEmpty())
            throw Http.erro(422, "Para a NFC-e real informe os itens (com NCM) e as formas de pagamento da venda");
        emitirReal(n, e.contingencia());
        return emitida(n);
    }

    /** Salva a nota e registra a emissão na auditoria (CPF mascarado). */
    private Nota emitida(Nota n) {
        Nota s = salvar(n);
        audit("VENDA", "NFC-e", s.id, "NFC-e nº " + s.numero + " emitida (venda #" + s.vendaId + ", R$ " + s.valor + ") · " + s.status
            + (s.cStat != null ? " cStat " + s.cStat : "") + " · " + (s.cpf == null ? "sem CPF" : "CPF ***" + s.cpf.substring(7)), null, null);
        return s;
    }

    /** Nota rejeitada por CPF/destinatário: o caixa corrige o CPF (ou deixa em branco = sem CPF) e reenvia, mantendo o mesmo número. */
    @POST @Path("/corrigir-cpf/{id}")
    public synchronized Nota corrigirCpf(@PathParam("id") Long id, CorrigirCpf c) {
        Nota n = achar(id);
        if (!"REJEITADA".equals(n.status)) throw Http.erro(422, "Só é possível corrigir nota rejeitada");
        String cpf; try { cpf = Cpf.normalizarOuNulo(c == null ? null : c.cpf()); } catch (IllegalArgumentException ex) { throw Http.erro(422, "CPF inválido"); }
        String antes = n.cpf; n.cpf = cpf; if (c != null && c.nome() != null) n.nome = c.nome();
        if (!fiscal.real()) { simular(n, false); }
        else emitirReal(n, false);
        audit("EDICAO", "NFC-e", id, "NFC-e nº " + n.numero + ": CPF " + (antes == null ? "(sem)" : "***" + antes.substring(7)) + " → " + (cpf == null ? "(sem CPF)" : "***" + cpf.substring(7)) + " · " + n.status, null, null);
        return salvar(n);
    }

    @POST @Path("/{id}/transmitir")
    public synchronized Nota transmitir(@PathParam("id") Long id) {
        Nota n = achar(id);
        if (!"CONTINGENCIA".equals(n.status)) throw Http.erro(422, "Nota não está em contingência");
        if (!fiscal.real()) { n.status = "AUTORIZADA"; n.protocolo = "SIM" + System.currentTimeMillis(); n.cStat = 100; n.xMotivo = "Autorizado o uso da NFC-e (simulado)"; }
        else {
            try { aplicar(n, fiscal.emissor().transmitir(n.xml, n.chave, n.numero, n.tpEmis == null ? 9 : n.tpEmis)); }
            catch (Exception ex) { throw Http.erro(502, "Falha ao transmitir: " + ex.getMessage()); }
            if (n.status == null) { n.status = "CONTINGENCIA"; throw Http.erro(503, "SEFAZ-RS indisponível; a nota segue em contingência"); }
        }
        audit("EDICAO", "NFC-e", id, "NFC-e nº " + n.numero + " transmitida (" + n.status + (n.cStat != null ? " cStat " + n.cStat : "") + ")", null, null);
        return salvar(n);
    }

    @POST @Path("/{id}/cancelar")
    public synchronized Nota cancelar(@PathParam("id") Long id, Cancelar c) {
        Nota n = achar(id);
        if (!"AUTORIZADA".equals(n.status)) throw Http.erro(422, "Só é possível cancelar nota autorizada");
        if (fiscal.real()) {
            try {
                Resultado r = fiscal.emissor().cancelar(n.chave, n.protocolo, c == null ? null : c.justificativa());
                if (!"CANCELADA".equals(r.status)) throw Http.erro(422, "SEFAZ recusou o cancelamento: " + r.cStat + " - " + r.xMotivo);
                n.cStat = r.cStat; n.xMotivo = r.xMotivo;
            } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage());
            } catch (WebApplicationException ex) { throw ex;
            } catch (Exception ex) { throw Http.erro(502, "Falha ao cancelar na SEFAZ: " + ex.getMessage()); }
        }
        n.status = "CANCELADA";
        audit("CANCELAMENTO", "NFC-e", id, "NFC-e nº " + n.numero + " cancelada (venda #" + n.vendaId + ", R$ " + n.valor + ")", null, null);
        return salvar(n);
    }

    // ---------------------------------------------------------------- internos
    private void emitirReal(Nota n, boolean contingencia) {
        try {
            Emitir e = JSON.readValue(n.payload, Emitir.class);
            NfceEmissor em = fiscal.emissor();
            Resultado r = em.emitir(paraDados(n, e), contingencia);
            if (r.semComunicacao) {                                           // SEFAZ fora: segue o fluxo da loja em contingência offline
                LOG.warning("SEFAZ-RS sem comunicação: NFC-e nº " + n.numero + " será emitida em contingência (o número pode ficar sem uso → inutilizar)");
                n.numero = n.numero + 1;
                r = em.emitir(paraDados(n, e), true);
            }
            aplicar(n, r);
        } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage());
        } catch (IllegalStateException ex) { throw Http.erro(503, ex.getMessage());
        } catch (WebApplicationException ex) { throw ex;
        } catch (Exception ex) { LOG.severe("Falha na emissão da NFC-e: " + ex); throw Http.erro(500, "Falha na emissão da NFC-e: " + ex.getMessage()); }
    }

    static Dados paraDados(Nota n, Emitir e) {
        List<Dados.Item> itens = e.itens().stream().map(i -> new Dados.Item(i.codigo(), i.nome(), i.ncm(), i.unidade(), i.qtd(), i.valorUnit(), i.valor())).toList();
        List<Dados.Pag> pags = e.pagamentos().stream().map(p -> new Dados.Pag(p.tPag(), p.valor(), p.cnpjCredenciadora(), p.tBand(), p.cAut())).toList();
        return new Dados(n.numero, n.cpf, n.nome, itens, pags);
    }

    private static void aplicar(Nota n, Resultado r) {
        n.chave = r.chave; n.status = r.status; n.cStat = r.cStat; n.xMotivo = r.xMotivo; n.protocolo = r.protocolo; n.dhAutorizacao = r.dhAutorizacao;
        n.qrCode = r.qrCode; n.urlChave = r.urlChave; n.tpEmis = r.tpEmis == 0 ? n.tpEmis : r.tpEmis; if (r.cNF != null) n.cNF = r.cNF;
        n.xml = r.xmlAutorizado != null ? r.xmlAutorizado : r.xmlAssinado;
    }

    private void simular(Nota n, boolean contingencia) {
        n.chave = chaveSimulada(n.numero); n.tpEmis = contingencia ? 9 : 1;
        if (contingencia) { n.status = "CONTINGENCIA"; n.xMotivo = "Contingência (simulada)"; }
        else { n.status = "AUTORIZADA"; n.protocolo = "SIM" + System.currentTimeMillis(); n.cStat = 100; n.xMotivo = "Autorizado o uso da NFC-e (simulado)"; }
    }

    private void audit(String acao, String ent, Object id, String desc, Object a, Object d) {
        QuarkusTransaction.requiringNew().run(() -> auditor.registrar(acao, ent, id, desc, a, d));
    }
    private Nota salvar(Nota n) {
        return QuarkusTransaction.requiringNew().call(() -> { if (n.id == null) { n.persist(); return n; } return Nota.getEntityManager().merge(n); });
    }
    private int proximoNumero() {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Panache resolve o nome da entidade interna (entidade com nome explícito, ver Entities.java)
            Nota ultima = Nota.find("numero is not null order by numero desc").firstResult();
            return Math.max(ultima == null ? 1 : ultima.numero + 1, fiscal.numeroInicial());
        });
    }
    private Nota achar(Long id) { Nota n = Nota.findById(id); if (n == null) throw new NotFoundException(); return n; }

    /** Chave FICTÍCIA (simulador): UF + AAMM + CNPJ zerado + modelo 65 + série + número + tpEmis + código + DV. */
    static String chaveSimulada(int numero) {
        String base = "43" + DateTimeFormatter.ofPattern("yyMM").format(LocalDate.now()) + "00000000000000" + "65" + "001"
                + String.format("%09d", numero) + "1" + String.format("%08d", (int) (Math.random() * 99999999));
        return base + NfceXml.dv(base);
    }
}
