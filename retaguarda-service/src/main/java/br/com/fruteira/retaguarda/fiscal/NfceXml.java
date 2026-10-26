package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Monta o XML da NFC-e (modelo 65, layout 4.00). O CPF do consumidor vai no grupo {@code <dest>}. */
public final class NfceXml {
    public static final String NS = "http://www.portalfiscal.inf.br/nfe";
    static final String HOMOLOG_PRODUTO = "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";
    static final String HOMOLOG_DEST = "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";
    private NfceXml() {}

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static String n(BigDecimal v, int casas) { return v.setScale(casas, RoundingMode.HALF_UP).toPlainString(); }
    public static String limpa(String s, int max) { String t = s == null ? "" : s.trim().replaceAll("\\s+", " "); return t.length() > max ? t.substring(0, max) : t; }

    public static String dataHora(OffsetDateTime d) { return d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")); }
    public static OffsetDateTime agora() { return OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")).withNano(0); }

    /** UF(2) AAMM CNPJ(14) mod(2) série(3) nNF(9) tpEmis(1) cNF(8) DV(1) = 44 dígitos. */
    public static String chave(Config c, OffsetDateTime emi, int numero, int tpEmis, String cNF) { return chave(c, emi, numero, tpEmis, cNF, 65); }
    /** Mesma chave para NFC-e (modelo 65) ou NF-e (modelo 55). */
    public static String chave(Config c, OffsetDateTime emi, int numero, int tpEmis, String cNF, int modelo) {
        String base = String.format("%02d", c.cUF) + emi.format(DateTimeFormatter.ofPattern("yyMM")) + soDigitos(c.cnpj, 14) + String.format("%02d", modelo)
            + String.format("%03d", c.serie) + String.format("%09d", numero) + tpEmis + cNF;
        return base + dv(base);
    }
    public static String soDigitos(String s, int tam) { String d = s == null ? "" : s.replaceAll("\\D", ""); return String.format("%" + tam + "s", d).replace(' ', '0'); }
    public static int dv(String base) {
        int soma = 0, peso = 2;
        for (int i = base.length() - 1; i >= 0; i--) { soma += (base.charAt(i) - '0') * peso; peso = peso == 9 ? 2 : peso + 1; }
        int r = soma % 11; return r < 2 ? 0 : 11 - r;
    }
    public static String novoCNF() { return String.format("%08d", new java.security.SecureRandom().nextInt(99_999_999)); }
    /** cNF aleatório que a SEFAZ aceita: diferente do número da nota e sem padrão trivial (00000000, 11111111…, 12345678, 87654321). */
    public static String novoCNF(int numero) {
        String c;
        do c = novoCNF(); while (cnfInvalido(c, numero));
        return c;
    }
    static boolean cnfInvalido(String c, int numero) {
        if (c.equals(String.format("%08d", numero))) return true;
        if (c.chars().distinct().count() == 1) return true;                                    // todos os dígitos iguais
        return "0123456789".contains(c) || "9876543210".contains(c) || "1234567890".contains(c); // sequência crescente/decrescente
    }

    /** XML da NFC-e sem assinatura e sem infNFeSupl. {@code tpEmis}: 1 normal · 9 contingência offline. Confere os dados obrigatórios antes de montar (ver {@link Validador}). */
    public static String montar(Config c, Dados d, String chave, OffsetDateTime emi, int tpEmis, String cNF) {
        List<Validador.Problema> problemas = Validador.nota(c, d);
        if (!problemas.isEmpty()) throw new IllegalArgumentException(Validador.resumo(problemas));
        Det dt = det(c, d.itens()); BigDecimal vNF = dt.vNF; String det = dt.det;
        BigDecimal pago = BigDecimal.ZERO; StringBuilder pag = new StringBuilder("<pag>");
        for (Dados.Pag p : d.pagamentos()) {
            pago = pago.add(p.valor());
            pag.append("<detPag><indPag>").append(p.indPag()).append("</indPag><tPag>").append(p.tPag()).append("</tPag>");
            if ("99".equals(p.tPag())) pag.append("<xPag>Outros</xPag>");
            pag.append("<vPag>").append(n(p.valor(), 2)).append("</vPag>");
            if (("03".equals(p.tPag()) || "04".equals(p.tPag()))) {
                boolean integrado = p.cnpjCredenciadora() != null && !p.cnpjCredenciadora().isBlank();
                pag.append("<card><tpIntegra>").append(integrado ? 1 : 2).append("</tpIntegra>");
                if (integrado) pag.append("<CNPJ>").append(p.cnpjCredenciadora().replaceAll("\\D", "")).append("</CNPJ>")
                    .append("<tBand>").append(p.tBand() == null || p.tBand().isBlank() ? "99" : p.tBand()).append("</tBand>")
                    .append(p.cAut() == null || p.cAut().isBlank() ? "" : "<cAut>" + esc(limpa(p.cAut(), 20)) + "</cAut>");
                pag.append("</card>");
            }
            pag.append("</detPag>");
        }
        BigDecimal troco = pago.subtract(vNF);
        if (troco.signum() > 0) pag.append("<vTroco>").append(n(troco, 2)).append("</vTroco>");
        pag.append("</pag>");
        StringBuilder x = new StringBuilder();
        x.append("<NFe xmlns=\"").append(NS).append("\"><infNFe versao=\"4.00\" Id=\"NFe").append(chave).append("\"><ide>")
         .append("<cUF>").append(c.cUF).append("</cUF><cNF>").append(cNF).append("</cNF><natOp>").append(esc(limpa(c.natOp.isBlank() ? "VENDA" : c.natOp, 60))).append("</natOp><mod>65</mod><serie>").append(c.serie).append("</serie><nNF>").append(d.numero()).append("</nNF>")
         .append("<dhEmi>").append(dataHora(emi)).append("</dhEmi><tpNF>1</tpNF><idDest>1</idDest><cMunFG>").append(c.cMun).append("</cMunFG><tpImp>4</tpImp><tpEmis>").append(tpEmis).append("</tpEmis>")
         .append("<cDV>").append(chave.charAt(43)).append("</cDV><tpAmb>").append(c.tpAmb).append("</tpAmb><finNFe>1</finNFe><indFinal>1</indFinal><indPres>1</indPres><procEmi>0</procEmi><verProc>").append(esc(c.verProc)).append("</verProc>");
        if (tpEmis == 9) x.append("<dhCont>").append(dataHora(emi)).append("</dhCont><xJust>").append(esc(c.justContingencia)).append("</xJust>");
        x.append("</ide><emit><CNPJ>").append(soDigitos(c.cnpj, 14)).append("</CNPJ><xNome>").append(esc(limpa(c.razao, 60))).append("</xNome>");
        if (!c.fantasia.isBlank()) x.append("<xFant>").append(esc(limpa(c.fantasia, 60))).append("</xFant>");
        x.append("<enderEmit><xLgr>").append(esc(limpa(c.logradouro, 60))).append("</xLgr><nro>").append(esc(limpa(c.numero, 60))).append("</nro><xBairro>").append(esc(limpa(c.bairro, 60))).append("</xBairro>")
         .append("<cMun>").append(c.cMun).append("</cMun><xMun>").append(esc(limpa(c.municipio, 60))).append("</xMun><UF>").append(c.uf).append("</UF><CEP>").append(c.cep.replaceAll("\\D", "")).append("</CEP></enderEmit>")
         .append("<IE>").append(c.ie.replaceAll("\\D", "")).append("</IE>");
        if (!c.im.isBlank()) x.append("<IM>").append(c.im.replaceAll("\\D", "")).append("</IM>");
        x.append("<CRT>").append(c.crt).append("</CRT></emit>");
        // Destinatário: CPF (Nota Fiscal Gaúcha) ou CNPJ. Sem documento, o grupo <dest> não existe (consumidor não identificado).
        String doc = Cadastro.digitos(d.cpf());
        if (!doc.isEmpty()) {
            x.append("<dest>").append(doc.length() == 14 ? "<CNPJ>" : "<CPF>").append(doc).append(doc.length() == 14 ? "</CNPJ>" : "</CPF>");
            String nome = limpa(d.nome(), 60);
            if (c.homologacao()) x.append("<xNome>").append(HOMOLOG_DEST).append("</xNome>");
            else if (nome.length() >= 2) x.append("<xNome>").append(esc(nome)).append("</xNome>");
            x.append("<indIEDest>9</indIEDest></dest>");
        }
        x.append(det).append(totalXml(c, dt));
        x.append("<transp><modFrete>9</modFrete></transp>").append(pag).append("</infNFe></NFe>");
        return x.toString();
    }

    /** Itens (det) e somas de impostos de uma nota: usado pela NFC-e (modelo 65) e pela NF-e (modelo 55). */
    record Det(String det, BigDecimal vNF, BigDecimal tBC, BigDecimal tIcms, BigDecimal tPis, BigDecimal tCofins, boolean ibs, BigDecimal tBcIbs, BigDecimal tIbsUf, BigDecimal tIbsMun, BigDecimal tCbs) {}
    static Det det(Config c, List<Dados.Item> itens) {
        BigDecimal vNF = BigDecimal.ZERO, tBC = BigDecimal.ZERO, tIcms = BigDecimal.ZERO, tPis = BigDecimal.ZERO, tCofins = BigDecimal.ZERO;
        BigDecimal tBcIbs = BigDecimal.ZERO, tIbsUf = BigDecimal.ZERO, tIbsMun = BigDecimal.ZERO, tCbs = BigDecimal.ZERO;
        boolean ibs = c.usaIbsCbs();
        StringBuilder det = new StringBuilder();
        int i = 0;
        for (Dados.Item it : itens) {
            i++; vNF = vNF.add(it.valor());
            String nome = c.homologacao() && i == 1 ? HOMOLOG_PRODUTO : limpa(it.nome(), 120);
            String un = limpa(it.unidade(), 6).isEmpty() ? "UN" : limpa(it.unidade(), 6);
            String orig = Validador.origem(c, it), cfop = Validador.cfop(c, it), cstIcms = Validador.cstIcms(c, it);
            det.append("<det nItem=\"").append(i).append("\"><prod><cProd>").append(esc(limpa(it.codigo() == null ? String.valueOf(i) : it.codigo(), 60))).append("</cProd><cEAN>SEM GTIN</cEAN>")
               .append("<xProd>").append(esc(nome)).append("</xProd><NCM>").append(it.ncm()).append("</NCM><CFOP>").append(cfop).append("</CFOP>")
               .append("<uCom>").append(esc(un)).append("</uCom><qCom>").append(n(it.qtd(), 4)).append("</qCom><vUnCom>").append(n(it.valorUnit(), 4)).append("</vUnCom><vProd>").append(n(it.valor(), 2)).append("</vProd>")
               .append("<cEANTrib>SEM GTIN</cEANTrib><uTrib>").append(esc(un)).append("</uTrib><qTrib>").append(n(it.qtd(), 4)).append("</qTrib><vUnTrib>").append(n(it.valorUnit(), 4)).append("</vUnTrib><indTot>1</indTot></prod><imposto>");
            // ICMS: Simples/MEI usa CSOSN; regime normal usa CST
            if (c.simples()) {
                det.append("<ICMS>").append("500".equals(cstIcms) ? "<ICMSSN500>" : "<ICMSSN102>").append("<orig>").append(orig).append("</orig><CSOSN>").append(cstIcms).append("</CSOSN>")
                   .append("500".equals(cstIcms) ? "</ICMSSN500>" : "</ICMSSN102>").append("</ICMS>");
            } else if ("00".equals(cstIcms)) {
                BigDecimal pIcms = Validador.aliqIcms(c, it), vIcms = it.valor().multiply(pIcms).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                tBC = tBC.add(it.valor()); tIcms = tIcms.add(vIcms);
                det.append("<ICMS><ICMS00><orig>").append(orig).append("</orig><CST>00</CST><modBC>3</modBC><vBC>").append(n(it.valor(), 2)).append("</vBC><pICMS>").append(n(pIcms, 4)).append("</pICMS><vICMS>").append(n(vIcms, 2)).append("</vICMS></ICMS00></ICMS>");
            } else {
                String g = "60".equals(cstIcms) ? "ICMS60" : "ICMS40";                        // 40/41/50 = isento, não tributado, suspenso · 60 = ST cobrada antes
                det.append("<ICMS><").append(g).append("><orig>").append(orig).append("</orig><CST>").append(cstIcms).append("</CST></").append(g).append("></ICMS>");
            }
            BigDecimal vPis = pisCofins(det, "PIS", Validador.cstPis(c, it), it.valor(), Validador.aliqPis(c, it));
            BigDecimal vCofins = pisCofins(det, "COFINS", Validador.cstCofins(c, it), it.valor(), Validador.aliqCofins(c, it));
            tPis = tPis.add(vPis); tCofins = tCofins.add(vCofins);
            if (ibs) {   // Reforma tributária (NT 2025.002): IBS (UF + município) e CBS por item, "por fora"
                BigDecimal red = it.pRedIbsCbs() == null ? BigDecimal.ZERO : it.pRedIbsCbs(), fator = BigDecimal.ONE.subtract(red.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                                BigDecimal vUf = it.valor().multiply(c.pIbsUf).multiply(fator).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                BigDecimal vMun = it.valor().multiply(c.pIbsMun).multiply(fator).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                BigDecimal vCbs = it.valor().multiply(c.pCbs).multiply(fator).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                tBcIbs = tBcIbs.add(it.valor()); tIbsUf = tIbsUf.add(vUf); tIbsMun = tIbsMun.add(vMun); tCbs = tCbs.add(vCbs);
                det.append("<IBSCBS><CST>").append(Validador.cstIbsCbs(c, it)).append("</CST><cClassTrib>").append(Validador.cClassTrib(c, it)).append("</cClassTrib><gIBSCBS><vBC>").append(n(it.valor(), 2)).append("</vBC>")
                   .append("<gIBSUF><pIBSUF>").append(n(c.pIbsUf, 4)).append("</pIBSUF>").append(gRed(red, c.pIbsUf)).append("<vIBSUF>").append(n(vUf, 2)).append("</vIBSUF></gIBSUF>")
                   .append("<gIBSMun><pIBSMun>").append(n(c.pIbsMun, 4)).append("</pIBSMun>").append(gRed(red, c.pIbsMun)).append("<vIBSMun>").append(n(vMun, 2)).append("</vIBSMun></gIBSMun>")
                   .append("<vIBS>").append(n(vUf.add(vMun), 2)).append("</vIBS>")
                   .append("<gCBS><pCBS>").append(n(c.pCbs, 4)).append("</pCBS>").append(gRed(red, c.pCbs)).append("<vCBS>").append(n(vCbs, 2)).append("</vCBS></gCBS></gIBSCBS></IBSCBS>");
            }
            det.append("</imposto></det>");
        }
        return new Det(det.toString(), vNF, tBC, tIcms, tPis, tCofins, ibs, tBcIbs, tIbsUf, tIbsMun, tCbs);
    }

    /** &lt;total&gt; com ICMSTot (e IBSCBSTot quando ligado). */
    static String totalXml(Config c, Det t) {
        BigDecimal vNF = t.vNF, tBC = t.tBC, tIcms = t.tIcms, tPis = t.tPis, tCofins = t.tCofins, tBcIbs = t.tBcIbs, tIbsUf = t.tIbsUf, tIbsMun = t.tIbsMun, tCbs = t.tCbs; boolean ibs = t.ibs;
        StringBuilder x = new StringBuilder();
        x.append("<total><ICMSTot><vBC>").append(n(tBC, 2)).append("</vBC><vICMS>").append(n(tIcms, 2)).append("</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet>")
         .append("<vProd>").append(n(vNF, 2)).append("</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>").append(n(tPis, 2)).append("</vPIS><vCOFINS>").append(n(tCofins, 2)).append("</vCOFINS><vOutro>0.00</vOutro><vNF>")
         .append(n(vNF, 2)).append("</vNF></ICMSTot>");
        if (ibs) x.append("<IBSCBSTot><vBCIBSCBS>").append(n(tBcIbs, 2)).append("</vBCIBSCBS><gIBS><gIBSUF><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSUF>").append(n(tIbsUf, 2)).append("</vIBSUF></gIBSUF>")
            .append("<gIBSMun><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSMun>").append(n(tIbsMun, 2)).append("</vIBSMun></gIBSMun><vIBS>").append(n(tIbsUf.add(tIbsMun), 2)).append("</vIBS><vCredPres>0.00</vCredPres><vCredPresCondSus>0.00</vCredPresCondSus></gIBS>")
            .append("<gCBS><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vCBS>").append(n(tCbs, 2)).append("</vCBS><vCredPres>0.00</vCredPres><vCredPresCondSus>0.00</vCredPresCondSus></gCBS></IBSCBSTot>");
        x.append("</total>");
        return x.toString();
    }

    /** PIS/COFINS: 01/02 tributado pela alíquota · 04 a 09 não tributado · 49/99 outras operações (valores da alíquota, zero se não informada). Devolve o valor do tributo. */
    static BigDecimal pisCofins(StringBuilder det, String t, String cst, BigDecimal base, BigDecimal aliq) {
        BigDecimal a = aliq == null ? BigDecimal.ZERO : aliq, v = base.multiply(a).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        String p = "PIS".equals(t) ? "pPIS" : "pCOFINS";
        if (Validador.CST_PIS_COFINS_NT.contains(cst)) det.append("<").append(t).append("><").append(t).append("NT><CST>").append(cst).append("</CST></").append(t).append("NT></").append(t).append(">");
        else {
            String g = Validador.CST_PIS_COFINS_ALIQ.contains(cst) ? "Aliq" : "Outr";
            det.append("<").append(t).append("><").append(t).append(g).append("><CST>").append(cst).append("</CST><vBC>").append(n(base, 2)).append("</vBC><").append(p).append(">").append(n(a, 4)).append("</").append(p).append("><v").append(t).append(">").append(n(v, 2)).append("</v").append(t).append("></").append(t).append(g).append("></").append(t).append(">");
        }
        return Validador.CST_PIS_COFINS_NT.contains(cst) ? BigDecimal.ZERO : v;
    }
    /** Grupo de redução de alíquota (gRed) quando o item tem redução de IBS/CBS. */
    static String gRed(BigDecimal red, BigDecimal aliq) {
        if (red == null || red.signum() == 0) return "";
        BigDecimal efetiva = aliq.multiply(BigDecimal.ONE.subtract(red.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
        return "<gRed><pRedAliq>" + n(red, 4) + "</pRedAliq><pAliqEfet>" + n(efetiva, 4) + "</pAliqEfet></gRed>";
    }

    public static BigDecimal totalNF(Dados d) { return d.itens().stream().map(Dados.Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add); }

    // ---------------- QR Code (NFC-e versão 2) ----------------
    static String sha1Hex(String s) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02X", b));
        return sb.toString();
    }
    /** Emissão normal (online): p=chave|2|tpAmb|idCSC|hash — hash = SHA-1(chave|2|tpAmb|idCSC + CSC). */
    /** O identificador do CSC vai no QR Code sem zeros à esquerda ("000001" → "1"). */
    static String idCsc(Config c) { String t = c.cscId == null ? "" : c.cscId.trim().replaceFirst("^0+(?=\\d)", ""); return t; }
    public static String qrOnline(Config c, String chave) throws Exception {
        String base = chave + "|2|" + c.tpAmb + "|" + idCsc(c);
        return c.urlQr + "?p=" + base + "|" + sha1Hex(base + c.csc);
    }
    /** Contingência offline (tpEmis 9): p=chave|2|tpAmb|dia|vNF|digVal|idCSC|hash — digVal = DigestValue (texto base64) em hexadecimal ASCII. */
    public static String qrOffline(Config c, String chave, OffsetDateTime emi, BigDecimal vNF, String digestValueBase64) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : digestValueBase64.trim().getBytes(StandardCharsets.US_ASCII)) hex.append(String.format("%02X", b));
        String base = chave + "|2|" + c.tpAmb + "|" + String.format("%02d", emi.getDayOfMonth()) + "|" + n(vNF, 2) + "|" + hex + "|" + idCsc(c);
        return c.urlQr + "?p=" + base + "|" + sha1Hex(base + c.csc);
    }
}
