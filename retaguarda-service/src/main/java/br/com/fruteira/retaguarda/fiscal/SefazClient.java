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
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.timeoutSeg));
        if (!cfg.certPath.isBlank()) b.sslContext(sslContext(cfg));
        this.http = b.build();
    }

    static SSLContext sslContext(Config c) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] senha = c.certSenha.toCharArray();
        try (var in = new FileInputStream(c.certPath)) { ks.load(in, senha); }
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

    /** Envia o lote (síncrono, indSinc=1) com uma NFC-e assinada. IOException = sem comunicação (aí vale a contingência). */
    public Retorno autorizar(String nfeAssinada, long idLote) throws Exception {
        String lote = "<enviNFe xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\"><idLote>" + idLote + "</idLote><indSinc>1</indSinc>" + nfeAssinada + "</enviNFe>";
        return interpretar(post(cfg.urlAutorizacao, envelope("NFeAutorizacao4", lote)));
    }

    /** Consulta a situação da NFC-e pela chave (antes de entrar em contingência após um timeout). */
    public Retorno consultar(String chave) throws Exception {
        String xml = "<consSitNFe xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\"><tpAmb>" + cfg.tpAmb + "</tpAmb><xServ>CONSULTAR</xServ><chNFe>" + chave + "</chNFe></consSitNFe>";
        return interpretar(post(cfg.urlConsulta, envelope("NFeConsultaProtocolo4", xml)));
    }

    /** Envia o evento de cancelamento já assinado (envEvento). */
    public Retorno evento(String envEventoXml) throws Exception { return interpretar(post(cfg.urlEvento, envelope("NFeRecepcaoEvento4", envEventoXml))); }

    String post(String url, String soap) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(cfg.timeoutSeg))
            .header("Content-Type", "application/soap+xml; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 500 || r.statusCode() == 404 || r.statusCode() == 403)
            throw new java.io.IOException("SEFAZ respondeu HTTP " + r.statusCode());
        return r.body();
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
