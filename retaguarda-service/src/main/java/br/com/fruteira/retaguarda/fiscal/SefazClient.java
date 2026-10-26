package br.com.fruteira.retaguarda.fiscal;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Comunicação SOAP 1.2 com os webservices da SEFAZ (NFeAutorizacao4, NfeConsulta4, RecepcaoEvento4) por TLS com certificado de cliente (mTLS). */
public class SefazClient {
    private final Config cfg;
    private final HttpClient http;

    public SefazClient(Config cfg) throws Exception {
        this.cfg = cfg; cfg.completarUrls();
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(cfg.timeoutSeg));
        if (Certificado.resolver(cfg.certPath) != null) b.sslContext(sslContext(cfg));
        this.http = b.build();
    }

    static SSLContext sslContext(Config c) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] senha = c.certSenha.toCharArray();
        java.io.File arq = Certificado.resolver(c.certPath);
        if (arq == null) throw new IllegalStateException("Certificado A1 não encontrado (FISCAL_CERT_PATH ou certificado.pfx na raiz)");
        try (var in = new FileInputStream(arq)) { ks.load(in, senha); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, senha);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (c.trustPath.isBlank()) tmf.init((KeyStore) null);                      // cacerts do JDK
        else {
            KeyStore ts = KeyStore.getInstance(c.trustPath.toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12");
            try (var in = new FileInputStream(c.trustPath)) { ts.load(in, c.trustSenha.toCharArray()); }
            tmf.init(ts);
        }
        SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null); return ctx;
    }

    /** Resposta da SEFAZ já interpretada. */
    public record Retorno(int cStatLote, String xMotivoLote, int cStat, String xMotivo, String protocolo, String dhRecbto, String protNFeXml, String xmlBruto) {}

    static String envelope(String servico, String corpo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\"><soap12:Body>"
            + "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/" + servico + "\">" + corpo + "</nfeDadosMsg></soap12:Body></soap12:Envelope>";
    }

    /** NF-e (modelo 55): mesmos serviços, em outros endereços. */
    public Retorno autorizarNfe(String nfeAssinada, long idLote) throws Exception { return autorizar(cfg.urlNfeAutorizacao, nfeAssinada, idLote); }
    public Retorno consultarNfe(String chave) throws Exception { return consultar(cfg.urlNfeConsulta, chave); }
    public Retorno eventoNfe(String envEventoXml) throws Exception { return interpretar(post(cfg.urlNfeEvento, envelope("NFeRecepcaoEvento4", envEventoXml))); }

    /** Envia o lote (síncrono, indSinc=1) com uma NFC-e assinada. IOException = sem comunicação (aí vale a contingência). */
    public Retorno autorizar(String nfeAssinada, long idLote) throws Exception { return autorizar(cfg.urlAutorizacao, nfeAssinada, idLote); }
    Retorno autorizar(String url, String nfeAssinada, long idLote) throws Exception {
        String lote = "<enviNFe xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\"><idLote>" + idLote + "</idLote><indSinc>1</indSinc>" + nfeAssinada + "</enviNFe>";
        return interpretar(post(url, envelope("NFeAutorizacao4", lote)));
    }

    /** Consulta a situação da NFC-e pela chave (antes de entrar em contingência após um timeout). */
    public Retorno consultar(String chave) throws Exception { return consultar(cfg.urlConsulta, chave); }
    Retorno consultar(String url, String chave) throws Exception {
        String xml = "<consSitNFe xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\"><tpAmb>" + cfg.tpAmb + "</tpAmb><xServ>CONSULTAR</xServ><chNFe>" + chave + "</chNFe></consSitNFe>";
        return interpretar(post(url, envelope("NFeConsultaProtocolo4", xml)));
    }

    /** Envia o evento de cancelamento já assinado (envEvento). */
    public Retorno evento(String envEventoXml) throws Exception { return interpretar(post(cfg.urlEvento, envelope("NFeRecepcaoEvento4", envEventoXml))); }

    /** Operação SOAP de cada serviço 4.00 (vai no parâmetro action do Content-Type SOAP 1.2). */
    static String acao(String soap) {
        for (String[] p : new String[][] {{"NFeAutorizacao4", "nfeAutorizacaoLote"}, {"NFeConsultaProtocolo4", "nfeConsultaNF"}, {"NFeRecepcaoEvento4", "nfeRecepcaoEvento"}, {"NFeStatusServico4", "nfeStatusServicoNF"}})
            if (soap.contains("/wsdl/" + p[0] + "\"")) return "http://www.portalfiscal.inf.br/nfe/wsdl/" + p[0] + "/" + p[1];
        return null;
    }

    String post(String url, String soap) throws Exception {
        String acao = acao(soap);
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(cfg.timeoutSeg))
            .header("Content-Type", "application/soap+xml; charset=utf-8" + (acao == null ? "" : "; action=\"" + acao + "\""))
            .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = r.statusCode();
        if (sc >= 500 || sc == 404 || sc == 403 || sc == 408 || sc == 429) throw new java.io.IOException("SEFAZ respondeu HTTP " + sc);      // indisponível: vale contingência/pendência
        if (sc / 100 != 2 && !r.body().contains("cStat")) {                                                                                     // 400/401/415...: erro nosso, não da disponibilidade
            String b = r.body().replaceAll("\\s+", " "); throw new IllegalStateException("SEFAZ recusou a requisição (HTTP " + sc + "): " + (b.length() > 300 ? b.substring(0, 300) : b));
        }
        return r.body();
    }

    /** Situação do serviço da SEFAZ (consStatServ): prova que o certificado A1, o TLS e a URL funcionam, sem emitir nada. cStat 107 = em operação. */
    public Retorno statusServico(boolean nfe) throws Exception {
        String url = nfe ? cfg.urlNfeStatus : cfg.urlStatus;
        String xml = "<consStatServ xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\"><tpAmb>" + cfg.tpAmb + "</tpAmb><cUF>" + cfg.cUF + "</cUF><xServ>STATUS</xServ></consStatServ>";
        return interpretar(post(url, envelope("NFeStatusServico4", xml)));
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);          // sem XXE
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    static String txt(Node ctx, String local) {
        if (ctx == null) return null;
        NodeList l = ctx instanceof Document d ? d.getElementsByTagNameNS("*", local) : ((Element) ctx).getElementsByTagNameNS("*", local);
        return l.getLength() == 0 ? null : l.item(0).getTextContent().trim();
    }
    static int inteiro(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    static String serializar(Node n) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter w = new StringWriter(); t.transform(new DOMSource(n), new StreamResult(w)); return w.toString();
    }

    /** Lê retEnviNFe / retConsSitNFe / retEnvEvento: o resultado da NFC-e é o do protNFe/infProt (ou retEvento/infEvento), senão o do lote. */
    static Retorno interpretar(String soap) throws Exception {
        Document doc = parse(soap);
        Element raiz = doc.getDocumentElement();
        NodeList prot = doc.getElementsByTagNameNS("*", "infProt");
        NodeList ev = doc.getElementsByTagNameNS("*", "infEvento");
        // cStat do envelope (lote/consulta/evento): primeiro cStat fora de infProt/infEvento
        int cStatLote = 0; String xMotLote = null;
        NodeList todos = doc.getElementsByTagNameNS("*", "cStat");
        for (int i = 0; i < todos.getLength(); i++) {
            Node p = todos.item(i).getParentNode();
            if (!"infProt".equals(p.getLocalName()) && !"infEvento".equals(p.getLocalName())) { cStatLote = inteiro(todos.item(i).getTextContent().trim()); xMotLote = txt(p, "xMotivo"); break; }
        }
        Element info = prot.getLength() > 0 ? (Element) prot.item(0) : ev.getLength() > 0 ? (Element) ev.item(0) : null;
        if (info == null) return new Retorno(cStatLote, xMotLote, cStatLote, xMotLote, null, txt(doc, "dhRecbto"), null, soap);
        NodeList pn = doc.getElementsByTagNameNS("*", "protNFe");
        String protXml = pn.getLength() > 0 ? serializar(pn.item(0)) : null;
        return new Retorno(cStatLote, xMotLote, inteiro(txt(info, "cStat")), txt(info, "xMotivo"), txt(info, "nProt"), txt(info, "dhRecbto"), protXml, soap);
    }
}
