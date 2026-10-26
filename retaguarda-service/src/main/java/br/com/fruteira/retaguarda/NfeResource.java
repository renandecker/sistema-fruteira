package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Cliente;
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
 * NF-e (modelo 55) para VENDA A EMPRESA (CNPJ) que exige nota fiscal eletrônica de contribuinte: destinatário do cadastro de clientes,
 * CFOP 5xxx/6xxx pela UF, frete, desconto, duplicatas, cancelamento e carta de correção. A NFC-e (consumidor final) continua em {@link NfceResource}.
 * provider=simulador: chave/protocolo fictícios. provider=sefaz: monta, assina (A1), autoriza (síncrono) na SEFAZ-RS. Só é usada quando alguém emite uma NF-e.
 */
@Path("/nfe") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class NfeResource {
    static final Logger LOG = Logger.getLogger("fiscal");
    static final ObjectMapper JSON = new ObjectMapper();
    @Inject Auditor auditor;
    @Inject FiscalConfig fiscal;

    /** venc = AAAA-MM-DD */
    public record ParcelaIn(String venc, BigDecimal valor) {}
    /** clienteDoc = CNPJ (ou CPF) de um cliente JÁ cadastrado com endereço e IE; itens e pagamentos vêm da venda (ver NfceResource). */
    public record Emitir(Long vendaId, String clienteDoc, boolean consumidorFinal, Integer modFrete, BigDecimal vFrete, BigDecimal vDesc, String natOp, String infCpl, Integer indPres,
                         List<NfceResource.ItemIn> itens, List<NfceResource.PagIn> pagamentos, List<ParcelaIn> duplicatas) {}
    public record Cancelar(String justificativa) {}
    public record Carta(String texto) {}
    public record Conferencia(boolean ok, List<String> problemas) {}

    @GET public List<Nota> listar() { return Nota.find("modelo = '55' order by id desc").list(); }

    @GET @Path("/status")
    public Map<String, Object> status() {
        var pend = fiscal.real() ? fiscal.pendencias().stream().filter(x -> !x.startsWith("FISCAL_CSC")).toList() : List.<String>of();      // NF-e não usa CSC
        return Map.of("provider", fiscal.provider(), "real", fiscal.real(), "ambiente", fiscal.ambiente() == 1 ? "PRODUCAO" : "HOMOLOGACAO", "pendencias", pend,
            "problemasEmitente", fiscal.problemasEmitente().stream().map(Validador.Problema::toString).toList(), "ibsCbs", fiscal.ibsCbsLigado(),
            "certificado", fiscal.real() ? fiscal.certificadoResumo() : Map.of("ok", true, "simulado", true));
    }

    /** Teste da integração de NF-e: TLS com o certificado A1 + consStatServ no webservice de NF-e (cStat 107). Não emite nada. */
    @POST @Path("/sefaz-status")
    public Map<String, Object> sefazStatus() {
        if (!fiscal.real()) return Map.of("ok", true, "simulado", true, "mensagem", "Modo simulador: não há SEFAZ a consultar");
        try {
            fiscal.emissorNfe();
            var r = new SefazClient(fiscal.configAtual()).statusServico(true);
            return Map.of("ok", r.cStat() == 107, "cStat", r.cStat(), "mensagem", String.valueOf(r.xMotivo()));
        } catch (java.io.IOException | InterruptedException e) { return Map.of("ok", false, "mensagem", "Sem comunicação com a SEFAZ: " + e.getMessage()); }
        catch (Exception e) { return Map.of("ok", false, "mensagem", String.valueOf(e.getMessage())); }
    }

    @POST @Path("/conferir")
    public Conferencia conferir(Emitir e) {
        var l = new ArrayList<String>();
        fiscal.problemasEmitente().forEach(p -> l.add(p.toString()));
        try { Validador.nfe(fiscal.configAtual(), dados(1, e)).forEach(p -> l.add(p.toString())); }
        catch (WebApplicationException ex) { l.add("Destinatário: " + ex.getMessage()); }
        return new Conferencia(l.isEmpty(), l);
    }

    @GET @Path("/{id}/xml") @Produces(MediaType.APPLICATION_XML)
    public Response xml(@PathParam("id") Long id) {
        Nota n = achar(id); if (n.xml == null) throw new NotFoundException();
        return Response.ok(n.xml).header("Content-Disposition", "attachment; filename=\"" + n.chave + ".xml\"").build();
    }

    @POST @Path("/emitir")
    public synchronized Nota emitir(Emitir e) {
        if (e.vendaId() != null && QuarkusTransaction.requiringNew().call(() -> Nota.count("vendaId = ?1 and status in ('AUTORIZADA','CONTINGENCIA','PENDENTE')", e.vendaId())) > 0)
            throw Http.erro(409, "A venda #" + e.vendaId() + " já tem nota fiscal emitida (cancele-a antes de emitir outra)");
        Nota n = new Nota(); n.modelo = "55"; n.serie = fiscal.configAtual().serieNfe;
        DadosNfe base = dados(0, e);                                    // valida destinatário/itens/pagamentos antes de gastar um número
        n.vendaId = e.vendaId(); n.valor = base.totalNota(); n.cpf = Cadastro.digitos(base.dest().doc()); n.nome = base.dest().nome(); n.provider = fiscal.real() ? "SEFAZ" : "SIMULADOR";
        n.numero = proximoNumero();
        try { n.payload = JSON.writeValueAsString(e); } catch (Exception ex) { throw Http.erro(500, "Não foi possível guardar os dados da nota: " + ex.getMessage()); }
        if (!fiscal.real()) { simular(n); return salvarEAuditar(n, "emitida"); }
        conferirTudo(dados(n.numero, e));
        emitirReal(n, e);
        return salvarEAuditar(n, "emitida");
    }

    /** NF-e rejeitada: depois de corrigir o cadastro (ou o .env), reenvia com o mesmo número e os mesmos itens. */
    @POST @Path("/{id}/reemitir")
    public synchronized Nota reemitir(@PathParam("id") Long id) {
        Nota n = achar(id);
        if (!"REJEITADA".equals(n.status)) throw Http.erro(422, "Só é possível reemitir nota rejeitada");
        Emitir e; try { e = JSON.readValue(n.payload, Emitir.class); } catch (Exception ex) { throw Http.erro(422, "Dados originais da nota indisponíveis: emita novamente"); }
        if (!fiscal.real()) { simular(n); return salvarEAuditar(n, "reemitida"); }
        conferirTudo(dados(n.numero, e));
        emitirReal(n, e);
        return salvarEAuditar(n, "reemitida");
    }

    @POST @Path("/{id}/transmitir")
    public synchronized Nota transmitir(@PathParam("id") Long id) {
        Nota n = achar(id);
        if (!"PENDENTE".equals(n.status)) throw Http.erro(422, "Só é possível transmitir nota pendente");
        if (!fiscal.real()) { simular(n); return salvarEAuditar(n, "transmitida"); }
        try { aplicar(n, fiscal.emissorNfe().transmitir(n.xml, n.chave, n.numero)); }
        catch (IllegalStateException ex) { throw Http.erro(503, ex.getMessage()); }
        catch (Exception ex) { throw Http.erro(502, "Falha ao transmitir: " + ex.getMessage()); }
        if ("PENDENTE".equals(n.status)) throw Http.erro(503, "SEFAZ indisponível; a nota segue pendente");
        return salvarEAuditar(n, "transmitida");
    }

    @POST @Path("/{id}/cancelar")
    public synchronized Nota cancelar(@PathParam("id") Long id, Cancelar c) {
        Nota n = achar(id);
        if (!"AUTORIZADA".equals(n.status)) throw Http.erro(422, "Só é possível cancelar nota autorizada");
        if (fiscal.real()) {
            try {
                Resultado r = fiscal.emissorNfe().cancelar(n.chave, n.protocolo, c == null ? null : c.justificativa());
                if (!"OK".equals(r.status)) throw Http.erro(422, "SEFAZ recusou o cancelamento: " + r.cStat + " - " + r.xMotivo + " (o prazo de cancelamento é limitado; depois dele, só a devolução/estorno fiscal)");
                n.cStat = r.cStat; n.xMotivo = r.xMotivo;
            } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage());
            } catch (WebApplicationException ex) { throw ex;
            } catch (Exception ex) { throw Http.erro(502, "Falha ao cancelar na SEFAZ: " + ex.getMessage()); }
        }
        n.status = "CANCELADA";
        return salvarEAuditar(n, "cancelada");
    }

    /** Carta de correção eletrônica (CC-e): corrige dados acessórios (não valor, imposto, quantidade, destinatário nem datas). */
    @POST @Path("/{id}/carta-correcao")
    public synchronized Nota carta(@PathParam("id") Long id, Carta c) {
        Nota n = achar(id);
        if (!"AUTORIZADA".equals(n.status)) throw Http.erro(422, "Só é possível corrigir nota autorizada");
        int seq = (n.seqCce == null ? 0 : n.seqCce) + 1;
        if (fiscal.real()) {
            try {
                Resultado r = fiscal.emissorNfe().cartaCorrecao(n.chave, seq, c == null ? null : c.texto());
                if (!"OK".equals(r.status)) throw Http.erro(422, "SEFAZ recusou a carta de correção: " + r.cStat + " - " + r.xMotivo);
            } catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage());
            } catch (WebApplicationException ex) { throw ex;
            } catch (Exception ex) { throw Http.erro(502, "Falha ao enviar a carta de correção: " + ex.getMessage()); }
        } else if (c == null || c.texto() == null || c.texto().trim().length() < 15) throw Http.erro(422, "O texto da carta de correção deve ter de 15 a 1000 caracteres");
        n.seqCce = seq;
        return salvarEAuditar(n, "carta de correção nº " + seq + " registrada");
    }

    // ---------------------------------------------------------------- internos
    /** Monta os dados da NF-e: o destinatário vem do CADASTRO de clientes (endereço e IE completos). */
    DadosNfe dados(int numero, Emitir e) {
        String doc = Cadastro.digitos(e.clienteDoc());
        if (doc.isEmpty()) throw Http.erro(422, "Informe a empresa destinatária (CNPJ de um cliente cadastrado)");
        Cliente c = Cliente.find("cpf = ?1 and (ativo is null or ativo = true)", doc).firstResult();
        if (c == null) throw Http.erro(422, "Empresa não cadastrada: cadastre o CNPJ " + Cadastro.formatar(doc) + " em Cadastros → Clientes, com endereço e Inscrição Estadual");
        var dest = new DadosNfe.Dest(c.cpf, c.nome, c.ie, c.email, c.telefone, c.logradouro, c.numero, null, c.bairro, c.cMun, c.municipio, c.uf, c.cep);
        List<Dados.Item> itens = e.itens() == null ? List.of() : e.itens().stream().map(i -> new Dados.Item(i.codigo(), i.nome(), i.ncm(), i.unidade(), i.qtd(), i.valorUnit(), i.valor(),
            i.cfop(), i.cstIcms(), i.cstPis(), i.cstCofins(), i.origem(), i.aliqIcms(), i.aliqPis(), i.aliqCofins(), i.cstIbsCbs(), i.cClassTrib(), i.pRedIbsCbs())).toList();
        List<Dados.Pag> pags = e.pagamentos() == null ? List.of() : e.pagamentos().stream().map(p -> new Dados.Pag(p.tPag(), p.valor(), p.cnpjCredenciadora(), p.tBand(), p.cAut(), p.parcelas())).toList();
        List<DadosNfe.Parcela> dup = e.duplicatas() == null ? List.of() : e.duplicatas().stream().map(p -> new DadosNfe.Parcela(data(p.venc()), p.valor())).toList();
        DadosNfe d = new DadosNfe(numero, dest, itens, pags, dup, e.consumidorFinal(), e.modFrete() == null ? 9 : e.modFrete(), e.vFrete(), e.vDesc(),
            e.natOp() == null || e.natOp().isBlank() ? fiscal.configAtual().natOp : e.natOp(), e.infCpl(), e.indPres() == null ? 1 : e.indPres());
        if (numero == 0) {                                              // validação prévia (antes de reservar o número)
            var l = new ArrayList<>(fiscal.problemasEmitente()); l.addAll(Validador.nfe(fiscal.configAtual(), d));
            if (!l.isEmpty()) throw Http.erro(422, Validador.resumo(l));
        }
        return d;
    }

    private static LocalDate data(String s) {
        try { return s == null || s.isBlank() ? null : LocalDate.parse(s.trim()); } catch (Exception ex) { throw Http.erro(422, "Vencimento inválido (use AAAA-MM-DD): " + s); }
    }

    private void conferirTudo(DadosNfe d) {
        var l = new ArrayList<>(fiscal.problemasEmitente()); l.addAll(Validador.nfe(fiscal.configAtual(), d));
        if (!l.isEmpty()) throw Http.erro(422, Validador.resumo(l));
    }

    private void emitirReal(Nota n, Emitir e) {
        try { aplicar(n, fiscal.emissorNfe().emitir(dados(n.numero, e))); }
        catch (IllegalArgumentException ex) { throw Http.erro(422, ex.getMessage()); }
        catch (IllegalStateException ex) { throw Http.erro(503, ex.getMessage()); }
        catch (WebApplicationException ex) { throw ex; }
        catch (Exception ex) { LOG.severe("Falha na emissão da NF-e: " + ex); throw Http.erro(500, "Falha na emissão da NF-e: " + ex.getMessage()); }
    }

    private static void aplicar(Nota n, Resultado r) {
        n.chave = r.chave; n.status = r.status; n.cStat = r.cStat == 0 ? null : r.cStat; n.xMotivo = r.xMotivo; n.protocolo = r.protocolo; n.dhAutorizacao = r.dhAutorizacao; n.tpEmis = 1;
        if (r.cNF != null) n.cNF = r.cNF;
        n.xml = r.xmlAutorizado != null ? r.xmlAutorizado : r.xmlAssinado != null ? r.xmlAssinado : n.xml;
    }

    private void simular(Nota n) {
        if (n.chave == null) n.chave = chaveSimulada(n.numero, n.serie == null ? 1 : n.serie);
        n.status = "AUTORIZADA"; n.protocolo = "SIM" + System.currentTimeMillis(); n.cStat = 100; n.xMotivo = "Autorizado o uso da NF-e (simulado)"; n.tpEmis = 1;
    }

    private Nota salvarEAuditar(Nota n, String o) {
        Nota s = QuarkusTransaction.requiringNew().call(() -> { if (n.id == null) { n.persist(); return n; } return Nota.getEntityManager().merge(n); });
        QuarkusTransaction.requiringNew().run(() -> auditor.registrar("VENDA", "NF-e", s.id, "NF-e nº " + s.numero + " " + o + " · " + s.status + (s.cStat != null ? " cStat " + s.cStat : "") + " · R$ " + s.valor + " · " + (s.cpf == null ? "" : "doc ***" + s.cpf.substring(Math.max(0, s.cpf.length() - 4))), null, null));
        return s;
    }

    private int proximoNumero() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Nota ultima = Nota.find("numero is not null and modelo = '55' order by numero desc").firstResult();
            return Math.max(ultima == null ? 1 : ultima.numero + 1, fiscal.numeroInicialNfe());
        });
    }
    private Nota achar(Long id) { Nota n = Nota.findById(id); if (n == null || !"55".equals(n.modelo)) throw new NotFoundException(); return n; }

    /** Chave FICTÍCIA (simulador): UF + AAMM + CNPJ zerado + modelo 55 + série + número + tpEmis + código + DV. */
    static String chaveSimulada(int numero, int serie) {
        String base = "43" + DateTimeFormatter.ofPattern("yyMM").format(LocalDate.now()) + "00000000000000" + "55" + String.format("%03d", serie)
                + String.format("%09d", numero) + "1" + String.format("%08d", (int) (Math.random() * 99999999));
        return base + NfceXml.dv(base);
    }
}
