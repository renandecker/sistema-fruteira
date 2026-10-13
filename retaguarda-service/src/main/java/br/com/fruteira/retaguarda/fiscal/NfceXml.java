package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
    static String n(BigDecimal v, int casas) { return v.setScale(casas, RoundingMode.HALF_UP).toPlainString(); }
    static String limpa(String s, int max) { String t = s == null ? "" : s.trim().replaceAll("\\s+", " "); return t.length() > max ? t.substring(0, max) : t; }

    public static String dataHora(OffsetDateTime d) { return d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")); }
    public static OffsetDateTime agora() { return OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")).withNano(0); }

    /** UF(2) AAMM CNPJ(14) mod(2) série(3) nNF(9) tpEmis(1) cNF(8) DV(1) = 44 dígitos. */
    public static String chave(Config c, OffsetDateTime emi, int numero, int tpEmis, String cNF) {
        String base = String.format("%02d", c.cUF) + emi.format(DateTimeFormatter.ofPattern("yyMM")) + soDigitos(c.cnpj, 14) + "65"
            + String.format("%03d", c.serie) + String.format("%09d", numero) + tpEmis + cNF;
        return base + dv(base);
    }
    static String soDigitos(String s, int tam) { String d = s == null ? "" : s.replaceAll("\\D", ""); return String.format("%" + tam + "s", d).replace(' ', '0'); }
    public static int dv(String base) {
        int soma = 0, peso = 2;
        for (int i = base.length() - 1; i >= 0; i--) { soma += (base.charAt(i) - '0') * peso; peso = peso == 9 ? 2 : peso + 1; }
        int r = soma % 11; return r < 2 ? 0 : 11 - r;
    }
    public static String novoCNF() { return String.format("%08d", new java.security.SecureRandom().nextInt(99_999_999)); }

    /** XML da NFC-e sem assinatura e sem infNFeSupl. {@code tpEmis}: 1 normal · 9 contingência offline. */
    public static String montar(Config c, Dados d, String chave, OffsetDateTime emi, int tpEmis, String cNF) {
        if (d.itens() == null || d.itens().isEmpty()) throw new IllegalArgumentException("A NFC-e precisa de ao menos um item");
        BigDecimal vNF = BigDecimal.ZERO;
        StringBuilder det = new StringBuilder();
        int i = 0;
        for (Dados.Item it : d.itens()) {
            i++; vNF = vNF.add(it.valor());
            if (it.ncm() == null || !it.ncm().matches("\\d{8}")) throw new IllegalArgumentException("Item sem NCM de 8 dígitos: " + it.nome());
            String nome = c.homologacao() && i == 1 ? HOMOLOG_PRODUTO : limpa(it.nome(), 120);
            String un = limpa(it.unidade(), 6).isEmpty() ? "UN" : limpa(it.unidade(), 6);
            det.append("<det nItem=\"").append(i).append("\"><prod><cProd>").append(esc(limpa(it.codigo() == null ? String.valueOf(i) : it.codigo(), 60))).append("</cProd><cEAN>SEM GTIN</cEAN>")
               .append("<xProd>").append(esc(nome)).append("</xProd><NCM>").append(it.ncm()).append("</NCM><CFOP>").append(c.cfop).append("</CFOP>")
               .append("<uCom>").append(esc(un)).append("</uCom><qCom>").append(n(it.qtd(), 4)).append("</qCom><vUnCom>").append(n(it.valorUnit(), 4)).append("</vUnCom><vProd>").append(n(it.valor(), 2)).append("</vProd>")
               .append("<cEANTrib>SEM GTIN</cEANTrib><uTrib>").append(esc(un)).append("</uTrib><qTrib>").append(n(it.qtd(), 4)).append("</qTrib><vUnTrib>").append(n(it.valorUnit(), 4)).append("</vUnTrib><indTot>1</indTot></prod>")
               .append("<imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>").append(c.csosn).append("</CSOSN></ICMSSN102></ICMS>")
               .append("<PIS><PISNT><CST>07</CST></PISNT></PIS><COFINS><COFINSNT><CST>07</CST></COFINSNT></COFINS></imposto></det>");
        }
        BigDecimal pago = BigDecimal.ZERO; StringBuilder pag = new StringBuilder("<pag>");
        if (d.pagamentos() == null || d.pagamentos().isEmpty()) throw new IllegalArgumentException("A NFC-e precisa de ao menos uma forma de pagamento");
        for (Dados.Pag p : d.pagamentos()) {
            pago = pago.add(p.valor());
            pag.append("<detPag><tPag>").append(p.tPag()).append("</tPag><vPag>").append(n(p.valor(), 2)).append("</vPag>");
            if (("03".equals(p.tPag()) || "04".equals(p.tPag())) ) {
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
        if (troco.signum() < 0) throw new IllegalArgumentException("Pagamentos (R$ " + n(pago, 2) + ") menores que o total (R$ " + n(vNF, 2) + ")");
        if (troco.signum() > 0) pag.append("<vTroco>").append(n(troco, 2)).append("</vTroco>");
        pag.append("</pag>");
        StringBuilder x = new StringBuilder();
        x.append("<NFe xmlns=\"").append(NS).append("\"><infNFe versao=\"4.00\" Id=\"NFe").append(chave).append("\"><ide>")
         .append("<cUF>").append(c.cUF).append("</cUF><cNF>").append(cNF).append("</cNF><natOp>VENDA</natOp><mod>65</mod><serie>").append(c.serie).append("</serie><nNF>").append(d.numero()).append("</nNF>")
         .append("<dhEmi>").append(dataHora(emi)).append("</dhEmi><tpNF>1</tpNF><idDest>1</idDest><cMunFG>").append(c.cMun).append("</cMunFG><tpImp>4</tpImp><tpEmis>").append(tpEmis).append("</tpEmis>")
         .append("<cDV>").append(chave.charAt(43)).append("</cDV><tpAmb>").append(c.tpAmb).append("</tpAmb><finNFe>1</finNFe><indFinal>1</indFinal><indPres>1</indPres><procEmi>0</procEmi><verProc>").append(esc(c.verProc)).append("</verProc>");
        if (tpEmis == 9) x.append("<dhCont>").append(dataHora(emi)).append("</dhCont><xJust>").append(esc(c.justContingencia)).append("</xJust>");
        x.append("</ide><emit><CNPJ>").append(soDigitos(c.cnpj, 14)).append("</CNPJ><xNome>").append(esc(limpa(c.razao, 60))).append("</xNome>");
        if (!c.fantasia.isBlank()) x.append("<xFant>").append(esc(limpa(c.fantasia, 60))).append("</xFant>");
        x.append("<enderEmit><xLgr>").append(esc(limpa(c.logradouro, 60))).append("</xLgr><nro>").append(esc(limpa(c.numero, 60))).append("</nro><xBairro>").append(esc(limpa(c.bairro, 60))).append("</xBairro>")
         .append("<cMun>").append(c.cMun).append("</cMun><xMun>").append(esc(limpa(c.municipio, 60))).append("</xMun><UF>").append(c.uf).append("</UF><CEP>").append(c.cep.replaceAll("\\D", "")).append("</CEP></enderEmit>")
         .append("<IE>").append(c.ie.replaceAll("\\D", "")).append("</IE><CRT>").append(c.crt).append("</CRT></emit>");
        // Nota Fiscal Gaúcha: o CPF do consumidor vai no destinatário. Sem CPF, o grupo <dest> simplesmente não existe (consumidor não identificado).
        String cpf = Cpf.somenteDigitos(d.cpf());
        if (!cpf.isEmpty()) {
            if (!Cpf.valido(cpf)) throw new IllegalArgumentException("CPF inválido");
            x.append("<dest><CPF>").append(cpf).append("</CPF>");
            String nome = limpa(d.nome(), 60);
            if (c.homologacao()) x.append("<xNome>").append(HOMOLOG_DEST).append("</xNome>");
            else if (nome.length() >= 2) x.append("<xNome>").append(esc(nome)).append("</xNome>");
            x.append("<indIEDest>9</indIEDest></dest>");
        }
        x.append(det).append("<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet>")
         .append("<vProd>").append(n(vNF, 2)).append("</vProd><vFrete>0.00</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>")
         .append(n(vNF, 2)).append("</vNF></ICMSTot></total><transp><modFrete>9</modFrete></transp>").append(pag).append("</infNFe></NFe>");
        return x.toString();
    }

    public static BigDecimal totalNF(Dados d) { return d.itens().stream().map(Dados.Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add); }

    // ---------------- QR Code (NFC-e versão 2) ----------------
    static String sha1Hex(String s) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02X", b));
        return sb.toString();
    }
    /** Emissão normal (online): p=chave|2|tpAmb|idCSC|hash — hash = SHA-1(chave|2|tpAmb|idCSC + CSC). */
    public static String qrOnline(Config c, String chave) throws Exception {
        String base = chave + "|2|" + c.tpAmb + "|" + c.cscId;
        return c.urlQr + "?p=" + base + "|" + sha1Hex(base + c.csc);
    }
    /** Contingência offline (tpEmis 9): p=chave|2|tpAmb|dia|vNF|digVal|idCSC|hash — digVal = DigestValue (texto base64) em hexadecimal ASCII. */
    public static String qrOffline(Config c, String chave, OffsetDateTime emi, BigDecimal vNF, String digestValueBase64) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : digestValueBase64.trim().getBytes(StandardCharsets.US_ASCII)) hex.append(String.format("%02X", b));
        String base = chave + "|2|" + c.tpAmb + "|" + String.format("%02d", emi.getDayOfMonth()) + "|" + n(vNF, 2) + "|" + hex + "|" + c.cscId;
        return c.urlQr + "?p=" + base + "|" + sha1Hex(base + c.csc);
    }
}
