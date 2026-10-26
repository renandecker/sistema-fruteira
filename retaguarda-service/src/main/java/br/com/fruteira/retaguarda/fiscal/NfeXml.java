package br.com.fruteira.retaguarda.fiscal;

import static br.com.fruteira.retaguarda.fiscal.NfceXml.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * XML da NF-e (modelo 55, layout 4.00) de venda a empresa: destinatário completo (CNPJ, IE, endereço), CFOP 5xxx/6xxx conforme a UF,
 * frete e desconto rateados nos itens, duplicatas (cobr) e pagamento. Reaproveita a tributação (ICMS/PIS/COFINS/IBS/CBS) da NFC-e.
 */
public final class NfeXml {
    private NfeXml() {}
    static final String ZERO = "0.00";
    static final String COND_USO = "A Carta de Correcao e disciplinada pelo paragrafo 1o-A do art. 7o do Convenio S/N, de 15 de dezembro de 1970 e pode ser utilizada para regularizacao de erro ocorrido na emissao de documento fiscal, desde que o erro nao esteja relacionado com: I - as variaveis que determinam o valor do imposto tais como: base de calculo, aliquota, diferenca de preco, quantidade, valor da operacao ou da prestacao; II - a correcao de dados cadastrais que implique mudanca do remetente ou do destinatario; III - a data de emissao ou de saida.";
    static final String SIMPLES = "DOCUMENTO EMITIDO POR ME OU EPP OPTANTE PELO SIMPLES NACIONAL";

    /** UF(2) AAMM CNPJ(14) mod 55 série(3) nNF(9) tpEmis(1) cNF(8) DV(1) = 44 dígitos. */
    public static String chave(Config c, OffsetDateTime emi, int numero, String cNF) {
        String base = String.format("%02d", c.cUF) + emi.format(DateTimeFormatter.ofPattern("yyMM")) + soDigitos(c.cnpj, 14) + "55"
            + String.format("%03d", c.serieNfe) + String.format("%09d", numero) + 1 + cNF;
        return base + dv(base);
    }
    static int dv(String base) { return NfceXml.dv(base); }

    /** Divide um valor em partes proporcionais aos itens (a última leva a sobra de centavos). */
    static BigDecimal[] ratear(BigDecimal total, List<Dados.Item> itens, BigDecimal base) {
        BigDecimal[] r = new BigDecimal[itens.size()]; BigDecimal usado = BigDecimal.ZERO;
        for (int i = 0; i < r.length; i++) {
            r[i] = i == r.length - 1 ? total.subtract(usado) : base.signum() == 0 ? BigDecimal.ZERO : total.multiply(itens.get(i).valor()).divide(base, 2, RoundingMode.HALF_UP);
            usado = usado.add(r[i]);
        }
        return r;
    }

    public static String montar(Config c, DadosNfe d, String chave, OffsetDateTime emi, String cNF) {
        List<Validador.Problema> problemas = Validador.nfe(c, d);
        if (!problemas.isEmpty()) throw new IllegalArgumentException(Validador.resumo(problemas));
        DadosNfe.Dest t = d.dest();
        int idDest = d.idDest(c.uf); String prefixo = idDest == 2 ? "6" : "5";
        BigDecimal produtos = d.totalProdutos(), vNF = d.totalNota();
        BigDecimal[] frete = ratear(d.frete(), d.itens(), produtos), desc = ratear(d.desconto(), d.itens(), produtos);
        BigDecimal tBC = BigDecimal.ZERO, tIcms = BigDecimal.ZERO, tPis = BigDecimal.ZERO, tCofins = BigDecimal.ZERO, tBcIbs = BigDecimal.ZERO, tIbsUf = BigDecimal.ZERO, tIbsMun = BigDecimal.ZERO, tCbs = BigDecimal.ZERO;
        boolean ibs = c.usaIbsCbs(); StringBuilder det = new StringBuilder(); int i = 0;
        for (Dados.Item it : d.itens()) {
            BigDecimal vFr = frete[i], vDs = desc[i], base = it.valor().add(vFr).subtract(vDs); i++;
            String nome = c.homologacao() && i == 1 ? HOMOLOG_PRODUTO : limpa(it.nome(), 120);
            String un = limpa(it.unidade(), 6).isEmpty() ? "UN" : limpa(it.unidade(), 6);
            String orig = Validador.origem(c, it), cstIcms = Validador.cstIcms(c, it);
            det.append("<det nItem=\"").append(i).append("\"><prod><cProd>").append(esc(limpa(it.codigo() == null ? String.valueOf(i) : it.codigo(), 60))).append("</cProd><cEAN>SEM GTIN</cEAN>")
               .append("<xProd>").append(esc(nome)).append("</xProd><NCM>").append(it.ncm()).append("</NCM><CFOP>").append(Validador.cfopOp(c, it, prefixo)).append("</CFOP>")
               .append("<uCom>").append(esc(un)).append("</uCom><qCom>").append(n(it.qtd(), 4)).append("</qCom><vUnCom>").append(n(it.valorUnit(), 4)).append("</vUnCom><vProd>").append(n(it.valor(), 2)).append("</vProd>")
               .append("<cEANTrib>SEM GTIN</cEANTrib><uTrib>").append(esc(un)).append("</uTrib><qTrib>").append(n(it.qtd(), 4)).append("</qTrib><vUnTrib>").append(n(it.valorUnit(), 4)).append("</vUnTrib>");
            if (vFr.signum() > 0) det.append("<vFrete>").append(n(vFr, 2)).append("</vFrete>");
            if (vDs.signum() > 0) det.append("<vDesc>").append(n(vDs, 2)).append("</vDesc>");
            det.append("<indTot>1</indTot></prod><imposto>");
            if (c.simples()) {
                det.append("<ICMS>").append("500".equals(cstIcms) ? "<ICMSSN500>" : "<ICMSSN102>").append("<orig>").append(orig).append("</orig><CSOSN>").append(cstIcms).append("</CSOSN>")
                   .append("500".equals(cstIcms) ? "</ICMSSN500>" : "</ICMSSN102>").append("</ICMS>");
            } else if ("00".equals(cstIcms)) {
                BigDecimal pIcms = Validador.aliqIcms(c, it), vIcms = base.multiply(pIcms).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                tBC = tBC.add(base); tIcms = tIcms.add(vIcms);
                det.append("<ICMS><ICMS00><orig>").append(orig).append("</orig><CST>00</CST><modBC>3</modBC><vBC>").append(n(base, 2)).append("</vBC><pICMS>").append(n(pIcms, 4)).append("</pICMS><vICMS>").append(n(vIcms, 2)).append("</vICMS></ICMS00></ICMS>");
            } else {
                String g = "60".equals(cstIcms) ? "ICMS60" : "ICMS40";
                det.append("<ICMS><").append(g).append("><orig>").append(orig).append("</orig><CST>").append(cstIcms).append("</CST></").append(g).append("></ICMS>");
            }
            tPis = tPis.add(pisCofins(det, "PIS", Validador.cstPis(c, it), base, Validador.aliqPis(c, it)));
            tCofins = tCofins.add(pisCofins(det, "COFINS", Validador.cstCofins(c, it), base, Validador.aliqCofins(c, it)));
            if (ibs) {
                BigDecimal red = it.pRedIbsCbs() == null ? BigDecimal.ZERO : it.pRedIbsCbs(), fator = BigDecimal.ONE.subtract(red.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)), cem = new BigDecimal("100");
                BigDecimal vUf = base.multiply(c.pIbsUf).multiply(fator).divide(cem, 2, RoundingMode.HALF_UP), vMun = base.multiply(c.pIbsMun).multiply(fator).divide(cem, 2, RoundingMode.HALF_UP), vCbs = base.multiply(c.pCbs).multiply(fator).divide(cem, 2, RoundingMode.HALF_UP);
                tBcIbs = tBcIbs.add(base); tIbsUf = tIbsUf.add(vUf); tIbsMun = tIbsMun.add(vMun); tCbs = tCbs.add(vCbs);
                det.append("<IBSCBS><CST>").append(Validador.cstIbsCbs(c, it)).append("</CST><cClassTrib>").append(Validador.cClassTrib(c, it)).append("</cClassTrib><gIBSCBS><vBC>").append(n(base, 2)).append("</vBC>")
                   .append("<gIBSUF><pIBSUF>").append(n(c.pIbsUf, 4)).append("</pIBSUF>").append(gRed(red, c.pIbsUf)).append("<vIBSUF>").append(n(vUf, 2)).append("</vIBSUF></gIBSUF>")
                   .append("<gIBSMun><pIBSMun>").append(n(c.pIbsMun, 4)).append("</pIBSMun>").append(gRed(red, c.pIbsMun)).append("<vIBSMun>").append(n(vMun, 2)).append("</vIBSMun></gIBSMun>")
                   .append("<vIBS>").append(n(vUf.add(vMun), 2)).append("</vIBS><gCBS><pCBS>").append(n(c.pCbs, 4)).append("</pCBS>").append(gRed(red, c.pCbs)).append("<vCBS>").append(n(vCbs, 2)).append("</vCBS></gCBS></gIBSCBS></IBSCBS>");
            }
            det.append("</imposto></det>");
        }
        StringBuilder x = new StringBuilder();
        x.append("<NFe xmlns=\"").append(NS).append("\"><infNFe versao=\"4.00\" Id=\"NFe").append(chave).append("\"><ide>")
         .append("<cUF>").append(c.cUF).append("</cUF><cNF>").append(cNF).append("</cNF><natOp>").append(esc(limpa(d.natOp(), 60))).append("</natOp><mod>55</mod><serie>").append(c.serieNfe).append("</serie><nNF>").append(d.numero()).append("</nNF>")
         .append("<dhEmi>").append(dataHora(emi)).append("</dhEmi><tpNF>1</tpNF><idDest>").append(idDest).append("</idDest><cMunFG>").append(c.cMun).append("</cMunFG><tpImp>1</tpImp><tpEmis>1</tpEmis>")
         .append("<cDV>").append(chave.charAt(43)).append("</cDV><tpAmb>").append(c.tpAmb).append("</tpAmb><finNFe>1</finNFe><indFinal>").append(d.consumidorFinal() ? 1 : 0).append("</indFinal><indPres>").append(d.indPres())
         .append("</indPres><procEmi>0</procEmi><verProc>").append(esc(c.verProc)).append("</verProc></ide><emit><CNPJ>").append(soDigitos(c.cnpj, 14)).append("</CNPJ><xNome>").append(esc(limpa(c.razao, 60))).append("</xNome>");
        if (!c.fantasia.isBlank()) x.append("<xFant>").append(esc(limpa(c.fantasia, 60))).append("</xFant>");
        x.append("<enderEmit><xLgr>").append(esc(limpa(c.logradouro, 60))).append("</xLgr><nro>").append(esc(limpa(c.numero, 60))).append("</nro><xBairro>").append(esc(limpa(c.bairro, 60))).append("</xBairro>")
         .append("<cMun>").append(c.cMun).append("</cMun><xMun>").append(esc(limpa(c.municipio, 60))).append("</xMun><UF>").append(c.uf).append("</UF><CEP>").append(c.cep.replaceAll("\\D", "")).append("</CEP></enderEmit><IE>").append(c.ie.replaceAll("\\D", "")).append("</IE>");
        if (!c.im.isBlank()) x.append("<IM>").append(c.im.replaceAll("\\D", "")).append("</IM>");
        x.append("<CRT>").append(c.crt).append("</CRT></emit>");
        String doc = Cadastro.digitos(t.doc());
        x.append("<dest>").append(doc.length() == 14 ? "<CNPJ>" : "<CPF>").append(doc).append(doc.length() == 14 ? "</CNPJ>" : "</CPF>")
         .append("<xNome>").append(c.homologacao() ? HOMOLOG_DEST : esc(limpa(t.nome(), 60))).append("</xNome><enderDest><xLgr>").append(esc(limpa(t.logradouro(), 60))).append("</xLgr><nro>").append(esc(limpa(t.numero(), 60))).append("</nro>");
        if (t.complemento() != null && !t.complemento().isBlank()) x.append("<xCpl>").append(esc(limpa(t.complemento(), 60))).append("</xCpl>");
        x.append("<xBairro>").append(esc(limpa(t.bairro(), 60))).append("</xBairro><cMun>").append(Cadastro.digitos(t.cMun())).append("</cMun><xMun>").append(esc(limpa(t.municipio(), 60))).append("</xMun><UF>").append(t.uf().trim().toUpperCase())
         .append("</UF><CEP>").append(Cadastro.digitos(t.cep())).append("</CEP><cPais>1058</cPais><xPais>BRASIL</xPais>");
        if (!Cadastro.digitos(t.fone()).isEmpty()) x.append("<fone>").append(Cadastro.digitos(t.fone())).append("</fone>");
        x.append("</enderDest><indIEDest>").append(d.indIEDest()).append("</indIEDest>");
        if (d.indIEDest() == 1) x.append("<IE>").append(Cadastro.digitos(t.ie())).append("</IE>");
        if (t.email() != null && !t.email().isBlank()) x.append("<email>").append(esc(limpa(t.email(), 60))).append("</email>");
        x.append("</dest>").append(det).append("<total><ICMSTot><vBC>").append(n(tBC, 2)).append("</vBC><vICMS>").append(n(tIcms, 2)).append("</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST><vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet>")
         .append("<vProd>").append(n(produtos, 2)).append("</vProd><vFrete>").append(n(d.frete(), 2)).append("</vFrete><vSeg>0.00</vSeg><vDesc>").append(n(d.desconto(), 2)).append("</vDesc><vII>0.00</vII><vIPI>0.00</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>")
         .append(n(tPis, 2)).append("</vPIS><vCOFINS>").append(n(tCofins, 2)).append("</vCOFINS><vOutro>0.00</vOutro><vNF>").append(n(vNF, 2)).append("</vNF></ICMSTot>");
        if (ibs) x.append("<IBSCBSTot><vBCIBSCBS>").append(n(tBcIbs, 2)).append("</vBCIBSCBS><gIBS><gIBSUF><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSUF>").append(n(tIbsUf, 2)).append("</vIBSUF></gIBSUF>")
            .append("<gIBSMun><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vIBSMun>").append(n(tIbsMun, 2)).append("</vIBSMun></gIBSMun><vIBS>").append(n(tIbsUf.add(tIbsMun), 2)).append("</vIBS><vCredPres>0.00</vCredPres><vCredPresCondSus>0.00</vCredPresCondSus></gIBS>")
            .append("<gCBS><vDif>0.00</vDif><vDevTrib>0.00</vDevTrib><vCBS>").append(n(tCbs, 2)).append("</vCBS><vCredPres>0.00</vCredPres><vCredPresCondSus>0.00</vCredPresCondSus></gCBS></IBSCBSTot>");
        x.append("</total><transp><modFrete>").append(d.modFrete()).append("</modFrete></transp>");
        if (d.duplicatas() != null && !d.duplicatas().isEmpty()) {
            x.append("<cobr><fat><nFat>").append(String.format("%09d", d.numero())).append("</nFat><vOrig>").append(n(vNF.add(d.desconto()), 2)).append("</vOrig><vDesc>").append(n(d.desconto(), 2)).append("</vDesc><vLiq>").append(n(vNF, 2)).append("</vLiq></fat>");
            int k = 0; for (DadosNfe.Parcela p : d.duplicatas()) x.append("<dup><nDup>").append(String.format("%03d", ++k)).append("</nDup><dVenc>").append(p.venc()).append("</dVenc><vDup>").append(n(p.valor(), 2)).append("</vDup></dup>");
            x.append("</cobr>");
        }
        x.append("<pag>");
        for (Dados.Pag p : d.pagamentos()) {
            x.append("<detPag><indPag>").append(d.duplicatas() != null && !d.duplicatas().isEmpty() && !"90".equals(p.tPag()) ? 1 : p.indPag()).append("</indPag><tPag>").append(p.tPag()).append("</tPag>");
            if ("99".equals(p.tPag())) x.append("<xPag>Outros</xPag>");
            x.append("<vPag>").append(n(p.valor(), 2)).append("</vPag>");
            if ("03".equals(p.tPag()) || "04".equals(p.tPag())) {
                boolean integrado = p.cnpjCredenciadora() != null && !p.cnpjCredenciadora().isBlank();
                x.append("<card><tpIntegra>").append(integrado ? 1 : 2).append("</tpIntegra>");
                if (integrado) x.append("<CNPJ>").append(p.cnpjCredenciadora().replaceAll("\\D", "")).append("</CNPJ><tBand>").append(p.tBand() == null || p.tBand().isBlank() ? "99" : p.tBand()).append("</tBand>")
                    .append(p.cAut() == null || p.cAut().isBlank() ? "" : "<cAut>" + esc(limpa(p.cAut(), 20)) + "</cAut>");
                x.append("</card>");
            }
            x.append("</detPag>");
        }
        x.append("</pag>");
        String inf = (d.infCpl() == null ? "" : d.infCpl().trim()) + (c.crt.equals("1") || c.crt.equals("4") ? (d.infCpl() == null || d.infCpl().isBlank() ? "" : " | ") + SIMPLES : "");
        if (!inf.isBlank()) x.append("<infAdic><infCpl>").append(esc(limpa(inf, 2000))).append("</infCpl></infAdic>");
        return x.append("</infNFe></NFe>").toString();
    }
}
