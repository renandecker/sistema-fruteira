package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Nota;
import br.com.fruteira.retaguarda.Entities.NotaImpressao;
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

    /** Classificação fiscal do produto (opcional: sem ela vale o padrão da loja no .env). */
    public record ItemIn(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor,
                         String cfop, String cstIcms, String cstPis, String cstCofins, String origem, BigDecimal aliqIcms, BigDecimal aliqPis, BigDecimal aliqCofins,
                         String cstIbsCbs, String cClassTrib, BigDecimal pRedIbsCbs) {}
    public record PagIn(String tPag, BigDecimal valor, String cnpjCredenciadora, String tBand, String cAut, Integer parcelas) {}
    public record Emitir(Long vendaId, BigDecimal valor, String cpf, String nome, boolean contingencia, String cartoes, List<ItemIn> itens, List<PagIn> pagamentos) {}
    public record CorrigirCpf(String cpf, String nome) {}
    public record Cancelar(String justificativa) {}
    public record ImpressaoIn(String origem, String terminal) {}
    public record ResultadoImpressao(String resultado, String erro) {}

    @GET public List<Nota> listar() { return Nota.find("modelo is null or modelo = '65' order by id desc").list(); }

    @GET @Path("/status")
    public Map<String, Object> status() {
        return Map.of("provider", fiscal.provider(), "real", fiscal.real(), "ambiente", fiscal.ambiente() == 1 ? "PRODUCAO" : "HOMOLOGACAO", "pendencias", fiscal.real() ? fiscal.pendencias() : List.of(),
            "problemasEmitente", fiscal.problemasEmitente().stream().map(Validador.Problema::toString).toList(), "ibsCbs", fiscal.ibsCbsLigado(), "exigeTef", fiscal.configAtual().exigeTef,
            "certificado", fiscal.real() ? fiscal.certificadoResumo() : Map.of("ok", true, "simulado", true));
    }

    /** Teste da integração: TLS com o certificado A1 + consStatServ na SEFAZ (cStat 107 = em operação). Não emite nada. */
    @POST @Path("/sefaz-status")
    public Map<String, Object> sefazStatus() {
        if (!fiscal.real()) return Map.of("ok", true, "simulado", true, "mensagem", "Modo simulador: não há SEFAZ a consultar");
        try {
            fiscal.emissor();                                                            // valida configuração e certificado
            var r = new SefazClient(fiscal.configAtual()).statusServico(false);
            return Map.of("ok", r.cStat() == 107, "cStat", r.cStat(), "mensagem", String.valueOf(r.xMotivo()));
        } catch (java.io.IOException | InterruptedException e) { return Map.of("ok", false, "mensagem", "Sem comunicação com a SEFAZ: " + e.getMessage()); }
        catch (Exception e) { return Map.of("ok", false, "mensagem", String.valueOf(e.getMessage())); }
    }

    public record Conferencia(boolean ok, List<String> problemas) {}
    /** Confere os dados obrigatórios da venda (itens, destinatário, tributação, pagamento/TEF) e do emitente SEM emitir nada. */
    @POST @Path("/conferir")
    public Conferencia conferir(Emitir e) {
        var l = new ArrayList<String>();
        fiscal.problemasEmitente().forEach(p -> l.add(p.toString()));
        if (e == null || e.itens() == null || e.itens().isEmpty()) l.add("Itens: a venda não tem itens");
        else {
            Nota tmp = new Nota(); tmp.numero = 1;
            try { tmp.cpf = Cpf.somenteDigitos(e.cpf()).isEmpty() ? null : Cpf.somenteDigitos(e.cpf()); } catch (Exception ignorada) { tmp.cpf = e.cpf(); }
            tmp.nome = e.nome();
            Validador.nota(fiscal.configAtual(), paraDados(tmp, e)).forEach(p -> l.add(p.toString()));
        }
        return new Conferencia(l.isEmpty(), l);
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

    // ---------------------------------------------------------------- DANFE guardado e tentativas de impressão
    private static final Map<String, String> MEIOS = Map.of("01", "DINHEIRO", "03", "CREDITO", "04", "DEBITO", "05", "CADERNO", "10", "VALE ALIMENTACAO", "11", "VALE REFEICAO", "17", "PIX");

    private static void exigirImprimivel(Nota n) {
        if (n.modelo != null && !"65".equals(n.modelo)) throw Http.erro(422, "Este DANFE é só da NFC-e (modelo 65)");
        if (!"AUTORIZADA".equals(n.status) && !"CONTINGENCIA".equals(n.status)) throw Http.erro(422, "Só imprime nota autorizada ou em contingência (status atual: " + n.status + ")");
    }

    /** Tudo o que o DANFE precisa, vindo da nota guardada (retorno da SEFAZ + itens/pagamentos enviados na emissão): reimprime mesmo sem o vendas-service. */
    @GET @Path("/{id}/danfe")
    public Map<String, Object> danfe(@PathParam("id") Long id) {
        Nota n = achar(id); exigirImprimivel(n);
        List<Map<String, Object>> itens = new ArrayList<>(), pagamentos = new ArrayList<>();
        if (n.payload != null) {
            try {
                Emitir e = JSON.readValue(n.payload, Emitir.class);
                if (e.itens() != null) for (ItemIn i : e.itens()) itens.add(Map.of("nome", String.valueOf(i.nome()), "quantidade", i.qtd(), "unidade", i.unidade() == null ? "UN" : i.unidade(), "precoUnit", i.valorUnit(), "subtotal", i.valor()));
                if (e.pagamentos() != null) for (PagIn p : e.pagamentos()) pagamentos.add(Map.of("meio", MEIOS.getOrDefault(p.tPag() == null ? "" : p.tPag(), "OUTROS"), "valor", p.valor()));
            } catch (Exception ex) { LOG.warning("Payload da NFC-e " + id + " ilegível: " + ex.getMessage()); }
        }
        var c = fiscal.configAtual();
        Map<String, Object> nota = new LinkedHashMap<>();
        nota.put("id", n.id); nota.put("numero", n.numero); nota.put("serie", n.serie == null ? c.serie : n.serie); nota.put("vendaId", n.vendaId); nota.put("status", n.status); nota.put("cStat", n.cStat); nota.put("xMotivo", n.xMotivo);
        nota.put("chave", n.chave); nota.put("protocolo", n.protocolo); nota.put("dhAutorizacao", n.dhAutorizacao); nota.put("cpf", n.cpf); nota.put("valor", n.valor); nota.put("data", n.data);
        nota.put("qrCode", n.qrCode); nota.put("urlChave", n.urlChave); nota.put("tpEmis", n.tpEmis); nota.put("tpAmb", fiscal.real() ? (fiscal.ambiente() == 1 ? 1 : 2) : 0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nota", nota); r.put("itens", itens); r.put("pagamentos", pagamentos);
        r.put("empresa", Map.of("nome", (c.fantasia == null || c.fantasia.isBlank()) ? String.valueOf(c.razao) : c.fantasia, "cnpj", c.cnpj == null ? "" : c.cnpj));
        r.put("tentativas", tentativas(id));
        return r;
    }

    private List<NotaImpressao> tentativas(Long notaId) { return NotaImpressao.list("notaId = ?1 order by tentativa", notaId); }

    @GET @Path("/{id}/impressoes")
    public List<NotaImpressao> impressoes(@PathParam("id") Long id) { achar(id); return tentativas(id); }

    /** Registra que o DANFE vai ser impresso agora (tentativa ENVIADA). O caixa confirma depois com /impressoes/{id}/resultado. */
    @POST @Path("/{id}/impressoes")
    public NotaImpressao registrarImpressao(@PathParam("id") Long id, ImpressaoIn in) {
        String origem = in == null || in.origem() == null ? "REIMPRESSAO" : in.origem().toUpperCase();
        if (!origem.equals("EMISSAO") && !origem.equals("REIMPRESSAO")) throw Http.erro(422, "Origem inválida: " + origem);
        String terminal = in == null || in.terminal() == null ? null : (in.terminal().length() > 60 ? in.terminal().substring(0, 60) : in.terminal());
        String usuario = auditor.usuario();
        NotaImpressao t = QuarkusTransaction.requiringNew().call(() -> {
            Nota n = achar(id); exigirImprimivel(n);
            NotaImpressao x = new NotaImpressao();
            x.notaId = id; x.tentativa = (n.tentativasImpressao == null ? 0 : n.tentativasImpressao) + 1; x.usuario = usuario; x.terminal = terminal; x.origem = origem; x.resultado = "ENVIADA";
            x.persist(); n.tentativasImpressao = x.tentativa; n.ultimaImpressao = "ENVIADA"; Nota.getEntityManager().merge(n);
            return x;
        });
        if (origem.equals("REIMPRESSAO")) audit("IMPRESSAO", "NFC-e", id, "DANFE da NFC-e reimpresso (tentativa " + t.tentativa + ", caixa " + (terminal == null ? "—" : terminal) + ")", null, null);
        return t;
    }

    /** O operador diz se o papel saiu: CONFIRMADA ou FALHA (com o motivo). Só a tentativa ainda ENVIADA pode receber resultado: o histórico não é reescrito. */
    @POST @Path("/impressoes/{iid}/resultado")
    public NotaImpressao resultadoImpressao(@PathParam("iid") Long iid, ResultadoImpressao r) {
        String res = r == null || r.resultado() == null ? "" : r.resultado().toUpperCase();
        if (!res.equals("CONFIRMADA") && !res.equals("FALHA")) throw Http.erro(422, "Resultado deve ser CONFIRMADA ou FALHA");
        String erro = r.erro() == null ? null : (r.erro().length() > 500 ? r.erro().substring(0, 500) : r.erro());
        NotaImpressao t = QuarkusTransaction.requiringNew().call(() -> {
            NotaImpressao x = NotaImpressao.findById(iid); if (x == null) throw new NotFoundException();
            if (!"ENVIADA".equals(x.resultado)) throw Http.erro(422, "Esta tentativa já tem resultado (" + x.resultado + ")");
            x.resultado = res; x.erro = res.equals("FALHA") ? erro : null;
            Nota n = Nota.findById(x.notaId);
            if (n != null) { if (res.equals("CONFIRMADA")) n.impressa = true; if (n.tentativasImpressao == null || n.tentativasImpressao.equals(x.tentativa)) n.ultimaImpressao = res; Nota.getEntityManager().merge(n); }
            return x;
        });
        if (res.equals("FALHA")) audit("IMPRESSAO", "NFC-e", t.notaId, "Falha ao imprimir o DANFE (tentativa " + t.tentativa + "): " + (erro == null ? "sem motivo informado" : erro), null, null);
        return t;
    }

    @POST @Path("/emitir")
    public synchronized Nota emitir(Emitir e) {
        String cpf; try { cpf = documentoOuNulo(e.cpf()); } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage()); }
        Nota n = new Nota();
        n.modelo = "65"; n.vendaId = e.vendaId(); n.valor = e.valor(); n.cpf = cpf; n.nome = e.nome();
        n.cartoes = e.cartoes() != null && e.cartoes().length() > 2000 ? e.cartoes().substring(0, 2000) : e.cartoes();
        n.numero = proximoNumero();
        try { n.payload = JSON.writeValueAsString(e); } catch (Exception ex) { /* sem payload: só não reenvia */ }
        if (!fiscal.real()) { simular(n, e.contingencia()); return emitida(n); }
        if (e.itens() == null || e.itens().isEmpty() || e.pagamentos() == null || e.pagamentos().isEmpty())
            throw Http.erro(422, "Para a NFC-e real informe os itens (com NCM) e as formas de pagamento da venda");
        confirmarDados(n, e);
        emitirReal(n, e.contingencia());
        return emitida(n);
    }

    /** CPF (11) ou CNPJ (14) do destinatário: vazio = sem identificação; inválido = erro. */
    static String documentoOuNulo(String s) {
        String d = Cadastro.digitos(s);
        if (d.isEmpty()) return null;
        if (d.length() == 11) { if (!Cpf.valido(d)) throw new IllegalArgumentException("CPF inválido"); return d; }
        if (d.length() == 14) { if (!Cadastro.cnpjValido(d)) throw new IllegalArgumentException("CNPJ inválido"); return d; }
        throw new IllegalArgumentException("Documento do destinatário deve ter 11 (CPF) ou 14 (CNPJ) dígitos");
    }

    /** Antes de gastar um número de nota: confere TODOS os dados obrigatórios e devolve o que corrigir (a venda não se perde: o caixa corrige e reemite). */
    private void confirmarDados(Nota n, Emitir e) {
        var l = new ArrayList<Validador.Problema>(fiscal.problemasEmitente());
        l.addAll(Validador.nota(fiscal.configAtual(), paraDados(n, e)));
        if (!l.isEmpty()) throw Http.erro(422, Validador.resumo(l));
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
        String cpf; try { cpf = documentoOuNulo(c == null ? null : c.cpf()); } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage()); }
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
        List<Dados.Item> itens = e.itens().stream().map(i -> new Dados.Item(i.codigo(), i.nome(), i.ncm(), i.unidade(), i.qtd(), i.valorUnit(), i.valor(),
            i.cfop(), i.cstIcms(), i.cstPis(), i.cstCofins(), i.origem(), i.aliqIcms(), i.aliqPis(), i.aliqCofins(), i.cstIbsCbs(), i.cClassTrib(), i.pRedIbsCbs())).toList();
        List<Dados.Pag> pags = e.pagamentos().stream().map(p -> new Dados.Pag(p.tPag(), p.valor(), p.cnpjCredenciadora(), p.tBand(), p.cAut(), p.parcelas())).toList();
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
            Nota ultima = Nota.find("numero is not null and (modelo is null or modelo = '65') order by numero desc").firstResult();
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
