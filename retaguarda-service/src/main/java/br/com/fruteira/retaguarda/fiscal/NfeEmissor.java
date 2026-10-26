package br.com.fruteira.retaguarda.fiscal;

import java.io.IOException;
import java.time.OffsetDateTime;
import org.w3c.dom.*;

/**
 * Emissor de NF-e (modelo 55) para venda a empresa: monta o XML, assina (A1), autoriza (síncrono) e trata cancelamento e carta de correção.
 * Sem comunicação com a SEFAZ a nota fica PENDENTE (já assinada) e é reenviada por {@link #transmitir}; não há contingência offline (EPEC/SVC) nesta versão.
 */
public class NfeEmissor {
    private final Config cfg; private final SefazClient sefaz; private final Assinador.Chave chave;
    public NfeEmissor(Config cfg, SefazClient sefaz, Assinador.Chave chave) { this.cfg = cfg; this.sefaz = sefaz; this.chave = chave; cfg.completarUrls(); }

    public Resultado emitir(DadosNfe d) throws Exception {
        OffsetDateTime emi = NfceXml.agora(); String cNF = NfceXml.novoCNF(d.numero()), ch = NfeXml.chave(cfg, emi, d.numero(), cNF);
        Resultado r = new Resultado(); r.chave = ch; r.numero = d.numero(); r.tpEmis = 1; r.cNF = cNF;
        Document doc = SefazClient.parse(NfeXml.montar(cfg, d, ch, emi, cNF));
        Element nfe = doc.getDocumentElement(), inf = (Element) nfe.getElementsByTagNameNS("*", "infNFe").item(0);
        Assinador.assinar(doc, nfe, inf, chave);
        r.xmlAssinado = SefazClient.serializar(doc);
        return enviar(r, 1);
    }

    /** Reenvia uma NF-e já assinada que ficou PENDENTE: consulta a chave antes (pode ter sido autorizada) e só então envia. */
    public Resultado transmitir(String xmlAssinado, String ch, int numero) throws Exception {
        Resultado r = new Resultado(); r.chave = ch; r.numero = numero; r.tpEmis = 1; r.xmlAssinado = xmlAssinado;
        return enviar(r, 2);
    }

    private Resultado enviar(Resultado r, long lote) throws Exception {
        SefazClient.Retorno ret = null;
        try { SefazClient.Retorno c = lote == 2 ? sefaz.consultarNfe(r.chave) : null; if (c != null && (c.cStat() == 100 || c.cStat() == 150)) ret = c; } catch (IOException | InterruptedException ignorada) { /* tenta enviar */ }
        if (ret == null) {
            try { ret = sefaz.autorizarNfe(NfceEmissor.semDeclaracao(r.xmlAssinado), lote); }
            catch (IOException | InterruptedException e) {                         // queda/timeout: a SEFAZ pode ter autorizado
                try { SefazClient.Retorno c = sefaz.consultarNfe(r.chave); if (c.cStat() == 100 || c.cStat() == 150) ret = c; } catch (IOException | InterruptedException e2) { /* sem rede */ }
                if (ret == null) { r.semComunicacao = true; r.status = "PENDENTE"; r.xMotivo = "Sem comunicação com a SEFAZ: a NF-e foi assinada e fica pendente para reenvio"; return r; }
            }
        }
        if (ret.cStat() == 204 || ret.cStat() == 539) { SefazClient.Retorno c = sefaz.consultarNfe(r.chave); if (c.cStat() == 100) ret = c; }   // duplicidade: já está na SEFAZ
        r.cStat = ret.cStat(); r.xMotivo = ret.xMotivo(); r.protocolo = ret.protocolo(); r.dhAutorizacao = ret.dhRecbto();
        if (r.cStat == 100 || r.cStat == 150) {
            r.status = "AUTORIZADA";
            if (ret.protNFeXml() != null) r.xmlAutorizado = "<nfeProc xmlns=\"" + NfceXml.NS + "\" versao=\"4.00\">" + NfceEmissor.semDeclaracao(r.xmlAssinado) + ret.protNFeXml() + "</nfeProc>";
        } else if (r.cStat == 110 || r.cStat == 301 || r.cStat == 302) r.status = "DENEGADA";
        else r.status = "REJEITADA";
        return r;
    }

    private Resultado evento(String ch, String tpEvento, int seq, String detalhe) throws Exception {
        String id = "ID" + tpEvento + ch + String.format("%02d", seq);
        String xml = "<envEvento xmlns=\"" + NfceXml.NS + "\" versao=\"1.00\"><idLote>1</idLote><evento versao=\"1.00\"><infEvento Id=\"" + id + "\"><cOrgao>" + cfg.cUF + "</cOrgao><tpAmb>" + cfg.tpAmb
            + "</tpAmb><CNPJ>" + NfceXml.soDigitos(cfg.cnpj, 14) + "</CNPJ><chNFe>" + ch + "</chNFe><dhEvento>" + NfceXml.dataHora(NfceXml.agora()) + "</dhEvento><tpEvento>" + tpEvento + "</tpEvento><nSeqEvento>" + seq
            + "</nSeqEvento><verEvento>1.00</verEvento>" + detalhe + "</infEvento></evento></envEvento>";
        Document doc = SefazClient.parse(xml);
        Element inf = (Element) doc.getElementsByTagNameNS("*", "infEvento").item(0);
        Assinador.assinar(doc, (Element) inf.getParentNode(), inf, chave);
        SefazClient.Retorno ret = sefaz.eventoNfe(SefazClient.serializar(doc));
        Resultado r = new Resultado(); r.chave = ch; r.cStat = ret.cStat(); r.xMotivo = ret.xMotivo(); r.protocolo = ret.protocolo(); r.dhAutorizacao = ret.dhRecbto();
        r.status = (r.cStat == 135 || r.cStat == 136 || r.cStat == 155) ? "OK" : "REJEITADA";
        return r;
    }

    /** Cancelamento (110111): justificativa de 15+ caracteres. status "OK" = evento registrado. */
    public Resultado cancelar(String ch, String protocolo, String justificativa) throws Exception {
        if (justificativa == null || justificativa.trim().length() < 15) throw new IllegalArgumentException("A justificativa do cancelamento precisa de 15 ou mais caracteres");
        return evento(ch, "110111", 1, "<detEvento versao=\"1.00\"><descEvento>Cancelamento</descEvento><nProt>" + protocolo + "</nProt><xJust>" + NfceXml.esc(NfceXml.limpa(justificativa, 255)) + "</xJust></detEvento>");
    }

    /** Carta de correção (110110): 15 a 1000 caracteres; não corrige valor, imposto, quantidade, destinatário nem datas. */
    public Resultado cartaCorrecao(String ch, int seq, String texto) throws Exception {
        String t = texto == null ? "" : texto.trim();
        if (t.length() < 15 || t.length() > 1000) throw new IllegalArgumentException("O texto da carta de correção deve ter de 15 a 1000 caracteres");
        if (seq < 1 || seq > 20) throw new IllegalArgumentException("Limite de 20 cartas de correção por NF-e");
        return evento(ch, "110110", seq, "<detEvento versao=\"1.00\"><descEvento>Carta de Correcao</descEvento><xCorrecao>" + NfceXml.esc(NfceXml.limpa(t, 1000)) + "</xCorrecao><xCondUso>" + NfeXml.COND_USO + "</xCondUso></detEvento>");
    }
}
