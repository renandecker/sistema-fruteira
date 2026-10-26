package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Testes (só JDK) dos dados obrigatórios: cadastro, destinatário, operação, tributação, pagamento/TEF e IBS/CBS. */
public class ValidadorTeste {
    static int ok = 0, falhas = 0;
    static void confere(boolean c, String nome) { if (c) { ok++; System.out.println("  ✔ " + nome); } else { falhas++; System.out.println("  ✘ FALHOU: " + nome); } }
    static BigDecimal d(String s) { return new BigDecimal(s); }
    static boolean tem(List<Validador.Problema> l, String trecho) { return l.stream().anyMatch(p -> (p.onde() + " " + p.mensagem()).toLowerCase().contains(trecho.toLowerCase())); }

    static Config loja() {
        Config c = new Config();
        c.cnpj = "12.345.678/0001-95"; c.razao = "FRUTEIRA CONVENTOS LTDA"; c.ie = "0170123456"; c.logradouro = "RUA DAS FRUTAS"; c.numero = "100"; c.bairro = "CENTRO";
        c.cMun = "4321105"; c.municipio = "CONVENTOS"; c.cep = "95865-000"; c.cscId = "1"; c.csc = "X";
        return c;
    }
    static Dados.Item item(String ncm) { return new Dados.Item("1", "Banana", ncm, "KG", d("2.000"), d("5.00"), d("10.00")); }
    static Dados venda(Dados.Item it, String doc, Dados.Pag... p) { return new Dados(1, doc, null, List.of(it), List.of(p)); }
    static Dados.Pag din() { return new Dados.Pag("01", d("10.00"), null, null, null); }

    public static void main(String[] a) throws Exception {
        System.out.println("Documentos e cadastro");
        confere(Cadastro.cnpjValido("12.345.678/0001-95"), "CNPJ válido com máscara");
        confere(!Cadastro.cnpjValido("12.345.678/0001-96"), "CNPJ com dígito errado");
        confere(!Cadastro.cnpjValido("11111111111111"), "CNPJ com dígitos iguais");
        confere(Cadastro.ieValida("017/0123456", "RS") && !Cadastro.ieValida("0170123457", "RS") && !Cadastro.ieValida("12345", "RS"), "IE do RS: dígito verificador e tamanho");
        confere(Cadastro.ieValida("224/3658792", "RS"), "IE do RS (segundo exemplo)");
        confere(Cadastro.codigoMunicipioValido("4321105", 43) && !Cadastro.codigoMunicipioValido("3550308", 43) && !Cadastro.codigoMunicipioValido("43211", 43), "código IBGE: 7 dígitos e UF");
        confere("CPF".equals(Cadastro.tipoDocumento("529.982.247-25")) && "CNPJ".equals(Cadastro.tipoDocumento("12345678000195")) && Cadastro.tipoDocumento("123") == null, "tipo do documento");
        confere("12.345.678/0001-95".equals(Cadastro.formatar("12345678000195")), "formata CNPJ");

        System.out.println("Emitente");
        confere(Validador.emitente(loja()).isEmpty(), "loja completa não tem problema");
        Config c = loja(); c.cnpj = "123"; c.logradouro = ""; c.bairro = " "; c.cep = "9586"; c.ie = ""; c.cMun = "";
        List<Validador.Problema> pe = Validador.emitente(c);
        confere(tem(pe, "CNPJ") && tem(pe, "logradouro") && tem(pe, "bairro") && tem(pe, "CEP") && tem(pe, "Inscrição Estadual") && tem(pe, "IBGE"), "emitente: lista TODOS os campos faltando de uma vez");
        c = loja(); c.ie = "0170123457"; confere(tem(Validador.emitente(c), "inválida"), "IE com dígito errado");
        c = loja(); c.cfop = "6102"; confere(tem(Validador.emitente(c), "CFOP"), "CFOP interestadual não vale na NFC-e");
        c = loja(); c.csosn = "900"; confere(tem(Validador.emitente(c), "CSOSN"), "CSOSN fora do suportado");
        c = loja(); c.crt = "3"; c.cstIcms = "102"; confere(tem(Validador.emitente(c), "CST do ICMS"), "regime normal com CSOSN é recusado");
        c = loja(); c.crt = "9"; confere(tem(Validador.emitente(c), "regime"), "CRT inválido");

        System.out.println("Operação (itens)");
        confere(Validador.nota(loja(), venda(item("08039000"), null, din())).isEmpty(), "venda correta passa");
        confere(tem(Validador.nota(loja(), venda(item("1234"), null, din())), "NCM"), "NCM curto");
        confere(tem(Validador.nota(loja(), venda(new Dados.Item("1", "Banana", "08039000", "", d("2"), d("5"), d("10")), null, din())), "unidade"), "sem unidade");
        confere(tem(Validador.nota(loja(), venda(new Dados.Item("1", "Banana", "08039000", "KG", d("2"), d("5"), d("12")), null, din())), "não confere"), "total ≠ qtd × unitário");
        confere(Validador.nota(loja(), venda(new Dados.Item("1", "Banana", "08039000", "KG", d("0.350"), d("7.90"), d("2.77")), null, new Dados.Pag("01", d("2.77"), null, null, null))).isEmpty(), "arredondamento de centavo é tolerado");
        confere(tem(Validador.nota(loja(), venda(new Dados.Item("1", "X", "08039000", "KG", d("1"), d("10"), d("10"), "6102", null, null, null, null), null, din())), "CFOP"), "CFOP do produto inválido");
        confere(tem(Validador.nota(loja(), venda(new Dados.Item("1", "X", "08039000", "KG", d("1"), d("10"), d("10"), null, "00", null, null, null), null, din())), "CSOSN"), "CST de regime normal em loja do Simples");

        System.out.println("Destinatário");
        confere(Validador.nota(loja(), venda(item("08039000"), "529.982.247-25", din())).isEmpty(), "CPF válido");
        confere(Validador.nota(loja(), venda(item("08039000"), "12345678000195", din())).isEmpty(), "CNPJ válido");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), "12345678000196", din())), "CNPJ inválido"), "CNPJ inválido");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), "52998224724", din())), "CPF inválido"), "CPF inválido");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), "12345", din())), "11 (CPF) ou 14"), "documento de tamanho estranho");

        System.out.println("Tributação (regime normal)");
        c = loja(); c.crt = "3"; c.cstIcms = "40"; c.cstPis = "06"; c.cstCofins = "06"; c.cstIbsCbs = "000"; c.cClassTrib = "000001";
        confere(Validador.emitente(c).isEmpty() && Validador.nota(c, venda(item("08039000"), null, din())).isEmpty(), "hortifrúti: ICMS 40, PIS/COFINS 06");
        Dados.Item trib = new Dados.Item("1", "X", "08039000", "UN", d("1"), d("10"), d("10"), "5102", "00", "01", "01", "0", d("12"), d("0.65"), d("3"), null, null, null);
        String xml = NfceXml.montar(c, venda(trib, null, din()), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<ICMS00>") && xml.contains("<vICMS>1.20</vICMS>") && xml.contains("<PISAliq>") && xml.contains("<vPIS>0.07</vPIS>") && xml.contains("<vCOFINS>0.30</vCOFINS>"), "ICMS 00 (12%), PIS 0,65% e COFINS 3% calculados");
        confere(xml.contains("<total><ICMSTot><vBC>10.00</vBC><vICMS>1.20</vICMS>"), "totais de ICMS batem com os itens");
        confere(!xml.contains("<ICMSSN102>") && xml.contains("<CRT>3</CRT>"), "regime normal não usa CSOSN");
        confere(tem(Validador.nota(c, venda(new Dados.Item("1", "X", "08039000", "UN", d("1"), d("10"), d("10"), "5102", "00", "07", "07", "0"), null, din())), "alíquota do ICMS"), "CST 00 sem alíquota é recusado");
        confere(tem(Validador.nota(c, venda(new Dados.Item("1", "X", "08039000", "UN", d("1"), d("10"), d("10"), "5102", "40", "01", "07", "0"), null, din())), "alíquota do PIS"), "PIS tributado sem alíquota é recusado");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"))); confere(true, "XML do regime normal é bem formado");

        System.out.println("Simples Nacional: classificação por produto");
        Dados.Item sn = new Dados.Item("1", "X", "08039000", "UN", d("1"), d("10"), d("10"), "5405", "500", "49", "49", "1");
        xml = NfceXml.montar(loja(), venda(sn, null, din()), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<CFOP>5405</CFOP>") && xml.contains("<ICMSSN500><orig>1</orig><CSOSN>500</CSOSN>") && xml.contains("<PISOutr><CST>49</CST>"), "CFOP, CSOSN, origem e PIS 49 do produto são usados");
        xml = NfceXml.montar(loja(), venda(item("08039000"), null, din()), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<CFOP>5102</CFOP>") && xml.contains("<ICMSSN102><orig>0</orig><CSOSN>102</CSOSN>") && xml.contains("<PISNT><CST>07</CST>"), "sem classificação própria vale o padrão da loja");
        confere(xml.contains("<natOp>VENDA DE MERCADORIA</natOp>"), "natureza da operação");
        confere(!xml.contains("<IBSCBS>"), "Simples: IBS/CBS fora (facultativo até 04/01/2027)");

        System.out.println("Pagamento e TEF");
        confere(Validador.nota(loja(), venda(item("08039000"), null, new Dados.Pag("01", d("20.00"), null, null, null))).isEmpty(), "dinheiro com troco");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), null, new Dados.Pag("17", d("20.00"), null, null, null))), "troco"), "troco sem dinheiro é recusado");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), null, new Dados.Pag("01", d("5.00"), null, null, null))), "menores"), "pagamento menor que o total");
        confere(tem(Validador.nota(loja(), venda(item("08039000"), null, new Dados.Pag("77", d("10.00"), null, null, null))), "inválido"), "meio de pagamento inválido");
        c = loja(); c.exigeTef = true;
        Dados.Pag semTef = new Dados.Pag("03", d("10.00"), null, null, null);
        List<Validador.Problema> pt = Validador.nota(c, venda(item("08039000"), null, semTef));
        confere(tem(pt, "dados do TEF") && tem(pt, "bandeira") && tem(pt, "autorização"), "exige TEF: cartão sem NSU/CNPJ/bandeira/autorização é recusado");
        confere(Validador.nota(c, venda(item("08039000"), null, new Dados.Pag("03", d("10.00"), "12345678000195", "01", "123456", 1))).isEmpty(), "cartão com dados do TEF passa");
        confere(tem(Validador.nota(c, venda(item("08039000"), null, new Dados.Pag("04", d("10.00"), "12345678000196", "01", "123456"))), "credenciadora"), "CNPJ da credenciadora inválido");
        confere(tem(Validador.nota(c, venda(item("08039000"), null, new Dados.Pag("04", d("10.00"), "12345678000195", "55", "1"))), "bandeira"), "bandeira inválida");
        confere(Validador.nota(loja(), venda(item("08039000"), null, semTef)).isEmpty(), "sem exigir TEF, cartão não integrado é aceito (tpIntegra 2)");
        xml = NfceXml.montar(c, venda(item("08039000"), null, new Dados.Pag("03", d("10.00"), "12.345.678/0001-95", "01", "A1B2C3", 3)), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<indPag>1</indPag><tPag>03</tPag>") && xml.contains("<tpIntegra>1</tpIntegra><CNPJ>12345678000195</CNPJ><tBand>01</tBand><cAut>A1B2C3</cAut>"), "crédito parcelado: indPag 1 e cartão integrado");
        xml = NfceXml.montar(loja(), venda(item("08039000"), null, new Dados.Pag("05", d("10.00"), null, null, null)), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<indPag>1</indPag><tPag>05</tPag>"), "caderno (crédito da loja) é a prazo");
        xml = NfceXml.montar(loja(), venda(item("08039000"), null, new Dados.Pag("99", d("10.00"), null, null, null)), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<tPag>99</tPag><xPag>Outros</xPag>"), "tPag 99 leva xPag");
        confere(xml.contains("<indPag>0</indPag>"), "à vista = indPag 0");

        System.out.println("Destinatário CNPJ no XML");
        xml = NfceXml.montar(loja(), venda(item("08039000"), "12.345.678/0001-95", din()), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<dest><CNPJ>12345678000195</CNPJ>") && xml.contains("<indIEDest>9</indIEDest>"), "<dest> com CNPJ");

        System.out.println("IBS/CBS (reforma tributária)");
        c = loja(); c.crt = "3"; c.cstIcms = "40"; c.cstPis = "06"; c.cstCofins = "06";
        confere(tem(Validador.emitente(c), "IBS/CBS") && c.usaIbsCbs(), "regime normal liga IBS/CBS (auto) e exige CST/cClassTrib");
        c.cstIbsCbs = "000"; c.cClassTrib = "000001";
        confere(Validador.emitente(c).isEmpty(), "com CST e cClassTrib padrão fica completo");
        xml = NfceXml.montar(c, venda(new Dados.Item("1", "X", "08039000", "UN", d("1"), d("100"), d("100"), null, null, null, null, null, null, null, null, null, null, null), null, new Dados.Pag("01", d("100"), null, null, null)), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<IBSCBS><CST>000</CST><cClassTrib>000001</cClassTrib><gIBSCBS><vBC>100.00</vBC>") && xml.contains("<vIBSUF>0.10</vIBSUF>") && xml.contains("<vCBS>0.90</vCBS>"), "IBS 0,1% + CBS 0,9% sobre o item");
        confere(xml.contains("<IBSCBSTot><vBCIBSCBS>100.00</vBCIBSCBS>") && xml.contains("<vIBS>0.10</vIBS>"), "total IBS/CBS bate com o item");
        Dados.Item red = new Dados.Item("1", "X", "08039000", "UN", d("1"), d("100"), d("100"), null, null, null, null, null, null, null, null, "200", "200003", d("100"));
        xml = NfceXml.montar(c, venda(red, null, new Dados.Pag("01", d("100"), null, null, null)), "4".repeat(44), NfceXml.agora(), 1, "12345678");
        confere(xml.contains("<CST>200</CST><cClassTrib>200003</cClassTrib>") && xml.contains("<gRed><pRedAliq>100.0000</pRedAliq><pAliqEfet>0.0000</pAliqEfet></gRed>") && xml.contains("<vCBS>0.00</vCBS>"), "item com redução de 100% (CST/cClassTrib do produto)");
        c.ibsCbs = "off"; confere(!c.usaIbsCbs() && !NfceXml.montar(c, venda(item("08039000"), null, din()), "4".repeat(44), NfceXml.agora(), 1, "12345678").contains("IBSCBS"), "FISCAL_IBSCBS=off desliga");
        c = loja(); c.ibsCbs = "on"; c.cstIbsCbs = "000"; c.cClassTrib = "000001";
        confere(c.usaIbsCbs() && Validador.emitente(c).isEmpty(), "FISCAL_IBSCBS=on no Simples (teste antecipado)");

        System.out.println((falhas == 0 ? "" : "COM FALHAS — ") + ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }
}
