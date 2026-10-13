package br.com.fruteira.retaguarda.fiscal;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;
import org.w3c.dom.*;

/** Orquestra a NFC-e: monta o XML (com CPF no dest), assina (A1), transmite à SEFAZ-RS e interpreta o retorno. */
public class NfceEmissor {
    private final Config cfg; private final SefazClient sefaz; private final Assinador.Chave chave;
    private static final Pattern MSG_CPF = Pattern.compile("(?i)cpf|destinat");

    public NfceEmissor(Config cfg, SefazClient sefaz, Assinador.Chave chave) { this.cfg = cfg; this.sefaz = sefaz; this.chave = chave; cfg.completarUrls(); }

    /** @param contingencia true = emite offline (tpEmis 9) sem tentar a SEFAZ; transmitir() envia depois. */
    public Resultado emitir(Dados d, boolean contingencia) throws Exception {
        OffsetDateTime emi = NfceXml.agora(); int tpEmis = contingencia ? 9 : 1; String cNF = NfceXml.novoCNF();
        String ch = NfceXml.chave(cfg, emi, d.numero(), tpEmis, cNF);
        Resultado r = new Resultado(); r.chave = ch; r.numero = d.numero(); r.tpEmis = tpEmis; r.cNF = cNF; r.urlChave = cfg.urlChave;
        Document doc = SefazClient.parse(NfceXml.montar(cfg, d, ch, emi, tpEmis, cNF));
        Element nfe = doc.getDocumentElement(), inf = (Element) nfe.getElementsByTagNameNS("*", "infNFe").item(0);
        Assinador.assinar(doc, nfe, inf, chave);
        String qr = contingencia ? NfceXml.qrOffline(cfg, ch, emi, NfceXml.totalNF(d), Assinador.digestValue(doc)) : NfceXml.qrOnline(cfg, ch);
        r.qrCode = qr;
        Element supl = doc.createElementNS(NfceXml.NS, "infNFeSupl");                 // entre infNFe e Signature
        Element q = doc.createElementNS(NfceXml.NS, "qrCode"); q.appendChild(doc.createCDATASection(qr)); supl.appendChild(q);
        Element u = doc.createElementNS(NfceXml.NS, "urlChave"); u.setTextContent(cfg.urlChave); supl.appendChild(u);
        nfe.insertBefore(supl, doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").item(0));
        r.xmlAssinado = SefazClient.serializar(doc);
        if (contingencia) { r.status = "CONTINGENCIA"; r.cStat = 0; r.xMotivo = "Emitida em contingência offline (tpEmis 9); transmitir à SEFAZ assim que houver conexão"; return r; }
        return transmitirInterno(r, 1);
    }

    /** Envia (ou reenvia) uma NFC-e já assinada — usado ao sair da contingência. */
    public Resultado transmitir(String xmlAssinado, String ch, int numero, int tpEmis) throws Exception {
        Resultado r = new Resultado(); r.chave = ch; r.numero = numero; r.tpEmis = tpEmis; r.xmlAssinado = xmlAssinado; r.urlChave = cfg.urlChave;
        Document d = SefazClient.parse(xmlAssinado);
        NodeList qr = d.getElementsByTagNameNS("*", "qrCode"); if (qr.getLength() > 0) r.qrCode = qr.item(0).getTextContent();
        return transmitirInterno(r, 2);
    }

    private Resultado transmitirInterno(Resultado r, long lote) throws Exception {
        SefazClient.Retorno ret;
        try { ret = sefaz.autorizar(semDeclaracao(r.xmlAssinado), lote); }
        catch (IOException | InterruptedException e) {                       // timeout/queda: a SEFAZ pode ter autorizado — consulta antes de decidir
            try { ret = sefaz.consultar(r.chave); if (ret.cStat() != 100 && ret.cStat() != 150) { r.semComunicacao = ret.cStatLote() == 0; ret = null; } }
            catch (IOException | InterruptedException e2) { r.semComunicacao = true; ret = null; }
        }
        if (ret == null) {
            r.status = null; r.xMotivo = r.semComunicacao ? "Sem comunicação com a SEFAZ" : "Nota não autorizada";
            if (r.semComunicacao) return r; else ret = new SefazClient.Retorno(0, null, 217, "NFC-e não consta na base da SEFAZ", null, null, null, null);
        }
        if (ret.cStat() == 204 || ret.cStat() == 539) {                      // duplicidade: já está na SEFAZ → pega o protocolo pela consulta
            SefazClient.Retorno c = sefaz.consultar(r.chave); if (c.cStat() == 100) ret = c;
        }
        r.cStat = ret.cStat(); r.xMotivo = ret.xMotivo(); r.protocolo = ret.protocolo(); r.dhAutorizacao = ret.dhRecbto();
        if (r.cStat == 100 || r.cStat == 150) {
            r.status = "AUTORIZADA";
            if (ret.protNFeXml() != null)
                r.xmlAutorizado = "<nfeProc xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\">" + semDeclaracao(r.xmlAssinado) + ret.protNFeXml() + "</nfeProc>";
        } else if (r.cStat == 110 || r.cStat == 301 || r.cStat == 302) r.status = "DENEGADA";
        else {
            r.status = "REJEITADA";
            r.erroCpf = r.cStat == 237 || (r.xMotivo != null && MSG_CPF.matcher(r.xMotivo).find());
        }
        return r;
    }

    /** Cancela (evento 110111) uma NFC-e autorizada. A justificativa precisa de 15+ caracteres. */
    public Resultado cancelar(String ch, String protocolo, String justificativa) throws Exception {
        if (justificativa == null || justificativa.trim().length() < 15) throw new IllegalArgumentException("A justificativa do cancelamento precisa de 15 ou mais caracteres");
        String id = "ID110111" + ch + "01";
        String xml = "<envEvento xmlns=\"" + NfceXml.NS + "\" versao=\"1.00\"><idLote>1</idLote><evento versao=\"1.00\"><infEvento Id=\"" + id + "\"><cOrgao>" + cfg.cUF + "</cOrgao><tpAmb>" + cfg.tpAmb
            + "</tpAmb><CNPJ>" + NfceXml.soDigitos(cfg.cnpj, 14) + "</CNPJ><chNFe>" + ch + "</chNFe><dhEvento>" + NfceXml.dataHora(NfceXml.agora())
            + "</dhEvento><tpEvento>110111</tpEvento><nSeqEvento>1</nSeqEvento><verEvento>1.00</verEvento><detEvento versao=\"1.00\"><descEvento>Cancelamento</descEvento><nProt>"
            + protocolo + "</nProt><xJust>" + NfceXml.esc(NfceXml.limpa(justificativa, 255)) + "</xJust></detEvento></infEvento></evento></envEvento>";
        Document doc = SefazClient.parse(xml);
        Element inf = (Element) doc.getElementsByTagNameNS("*", "infEvento").item(0);
        Assinador.assinar(doc, (Element) inf.getParentNode(), inf, chave);
        SefazClient.Retorno ret = sefaz.evento(SefazClient.serializar(doc));
        Resultado r = new Resultado(); r.chave = ch; r.cStat = ret.cStat(); r.xMotivo = ret.xMotivo(); r.protocolo = ret.protocolo(); r.dhAutorizacao = ret.dhRecbto();
        r.status = (r.cStat == 135 || r.cStat == 155) ? "CANCELADA" : "REJEITADA";
        return r;
    }

    static String semDeclaracao(String xml) { return xml.replaceFirst("^<\\?xml[^>]*\\?>", ""); }
    public BigDecimal total(Dados d) { return NfceXml.totalNF(d); }
}
