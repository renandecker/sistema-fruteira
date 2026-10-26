package br.com.fruteira.retaguarda.fiscal;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Testes (só JDK) do emissor de NF-e (modelo 55) para venda a empresa: XML, validações, assinatura, autorização, pendência, cancelamento e carta de correção. */
public class NfeTeste {
    static int ok = 0, falhas = 0;
    static void confere(boolean c, String nome) { if (c) { ok++; System.out.println("  ✔ " + nome); } else { falhas++; System.out.println("  ✘ FALHOU: " + nome); } }
    static BigDecimal d(String s) { return new BigDecimal(s); }
    static boolean tem(List<Validador.Problema> l, String t) { return l.stream().anyMatch(p -> (p.onde() + " " + p.mensagem()).toLowerCase().contains(t.toLowerCase())); }

    static Config loja() {
        Config c = new Config(); c.tpAmb = 2;
        c.cnpj = "12.345.678/0001-95"; c.razao = "FRUTEIRA CONVENTOS LTDA"; c.fantasia = "Fruteira"; c.ie = "0170123456"; c.logradouro = "RUA DAS FRUTAS"; c.numero = "100"; c.bairro = "CENTRO";
        c.cMun = "4321105"; c.municipio = "CONVENTOS"; c.cep = "95865-000"; c.timeoutSeg = 3;
        return c;
    }
    static DadosNfe.Dest mercado(String uf, String ie) { return new DadosNfe.Dest("00.000.000/0001-91", "MERCADO DO ZE LTDA", ie, "compras@mercado.com", "5130000000", "AV BRASIL", "500", "SALA 2", "CENTRO", "4314902", "PORTO ALEGRE", uf, "90010-000"); }
    static Dados.Item maca() { return new Dados.Item("5", "Maca gala", "08081000", "KG", d("10.000"), d("8.00"), d("80.00")); }
    static Dados.Item cebola() { return new Dados.Item("6", "Cebola", "07031019", "KG", d("20.000"), d("2.50"), d("50.00")); }
    static DadosNfe nota(DadosNfe.Dest dest, boolean cf, BigDecimal frete, BigDecimal desc, List<DadosNfe.Parcela> dup, String tPag) {
        BigDecimal vNF = d("130.00").add(frete == null ? BigDecimal.ZERO : frete).subtract(desc == null ? BigDecimal.ZERO : desc);
        return new DadosNfe(21, dest, List.of(maca(), cebola()), List.of(new Dados.Pag(tPag, vNF, null, null, null)), dup, cf, frete != null && frete.signum() > 0 ? 0 : 9, frete, desc, "VENDA DE MERCADORIA", "Pedido 77", 1);
    }

    public static void main(String[] a) throws Exception {
        Config c = loja();
        System.out.println("XML da NF-e (modelo 55)");
        var emi = NfceXml.agora(); String ch = NfeXml.chave(c, emi, 21, "12345678");
        confere(ch.length() == 44 && ch.substring(20, 22).equals("55") && ch.substring(22, 25).equals("001") && ch.substring(25, 34).equals("000000021"), "chave de 44 dígitos com modelo 55, série e número");
        String xml = NfeXml.montar(c, nota(mercado("RS", "0170123456"), false, null, null, null, "15"), ch, emi, "12345678");
        confere(xml.contains("<mod>55</mod>") && xml.contains("<tpImp>1</tpImp>") && xml.contains("<idDest>1</idDest>") && xml.contains("<indFinal>0</indFinal>"), "modelo 55, DANFE retrato, operação interna, revenda");
        confere(xml.contains("<dest><CNPJ>00000000000191</CNPJ>") && xml.contains("<enderDest><xLgr>AV BRASIL</xLgr><nro>500</nro><xCpl>SALA 2</xCpl><xBairro>CENTRO</xBairro><cMun>4314902</cMun>") && xml.contains("<cPais>1058</cPais>"), "destinatário com CNPJ e endereço completo");
        confere(xml.contains("<indIEDest>1</indIEDest><IE>0170123456</IE><email>compras@mercado.com</email></dest>"), "contribuinte: indIEDest 1, IE e e-mail");
        confere(xml.contains("<CFOP>5102</CFOP>") && !xml.contains("<CFOP>6"), "CFOP 5xxx dentro do RS");
        confere(xml.contains("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO") && xml.contains("NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO"), "homologação: textos obrigatórios do fisco");
        confere(xml.contains("<vProd>130.00</vProd><vFrete>0.00</vFrete>") && xml.contains("<vNF>130.00</vNF>"), "totais");
        confere(xml.contains("<infCpl>Pedido 77 | DOCUMENTO EMITIDO POR ME OU EPP OPTANTE PELO SIMPLES NACIONAL</infCpl>"), "informação complementar + texto do Simples Nacional");
        confere(!xml.contains("<infNFeSupl>") && !xml.contains("<qrCode>"), "NF-e não tem QR Code");
        javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))); confere(true, "XML bem formado");

        System.out.println("Venda interestadual, frete, desconto e duplicatas");
        List<DadosNfe.Parcela> dup = List.of(new DadosNfe.Parcela(LocalDate.now().plusDays(15), d("70.00")), new DadosNfe.Parcela(LocalDate.now().plusDays(30), d("63.00")));
        DadosNfe sp = nota(mercado("SP", "110042490114"), false, d("10.00"), d("7.00"), dup, "15");
        xml = NfeXml.montar(c, sp, ch, emi, "12345678");
        confere(xml.contains("<idDest>2</idDest>") && xml.contains("<CFOP>6102</CFOP>") && !xml.contains("<CFOP>5"), "interestadual: idDest 2 e CFOP 6102");
        confere(xml.contains("<vFrete>3.85</vFrete>") && xml.contains("<vFrete>6.15</vFrete>"), "frete rateado nos itens (a última leva a sobra)");
        confere(xml.contains("<vDesc>2.69</vDesc>") && xml.contains("<vDesc>4.31</vDesc>"), "desconto rateado nos itens");
        confere(xml.contains("<vProd>130.00</vProd><vFrete>10.00</vFrete><vSeg>0.00</vSeg><vDesc>7.00</vDesc>") && xml.contains("<vNF>133.00</vNF>") && xml.contains("<modFrete>0</modFrete>"), "total = produtos + frete − desconto");
        confere(xml.contains("<cobr><fat><nFat>000000021</nFat><vOrig>140.00</vOrig><vDesc>7.00</vDesc><vLiq>133.00</vLiq></fat><dup><nDup>001</nDup>") && xml.contains("<vDup>63.00</vDup>"), "cobrança: fatura e duplicatas");
        confere(xml.contains("<indPag>1</indPag><tPag>15</tPag>"), "a prazo (boleto): indPag 1");
        confere(xml.indexOf("</transp>") < xml.indexOf("<cobr>") && xml.indexOf("</cobr>") < xml.indexOf("<pag>"), "ordem transp → cobr → pag");

        System.out.println("Validações");
        confere(Validador.nfe(c, nota(mercado("RS", "0170123456"), false, null, null, null, "15")).isEmpty(), "nota correta passa");
        confere(tem(Validador.nfe(c, nota(null, false, null, null, null, "15")), "destinatário"), "sem destinatário");
        confere(tem(Validador.nfe(c, nota(new DadosNfe.Dest("12345678000196", "X LTDA", "ISENTO", null, null, "R", "1", null, "B", "4314902", "POA", "RS", "90010000"), false, null, null, null, "15")), "CNPJ"), "CNPJ inválido");
        confere(tem(Validador.nfe(c, nota(mercado("RS", "0170123457"), false, null, null, null, "15")), "Inscrição Estadual inválida"), "IE do RS com dígito errado");
        confere(tem(Validador.nfe(c, nota(mercado("RS", ""), false, null, null, null, "15")), "ISENTO"), "empresa sem IE: pede IE, ISENTO ou consumidor final");
        confere(Validador.nfe(c, nota(mercado("RS", "ISENTO"), false, null, null, null, "15")).isEmpty() && Validador.nfe(c, nota(mercado("RS", ""), true, null, null, null, "15")).isEmpty(), "ISENTO e consumidor final são aceitos");
        confere(xmlDe(c, nota(mercado("RS", "ISENTO"), false, null, null, null, "15")).contains("<indIEDest>2</indIEDest></dest>") || xmlDe(c, nota(mercado("RS", "ISENTO"), false, null, null, null, "15")).contains("<indIEDest>2</indIEDest><email>"), "isento: indIEDest 2 sem IE");
        DadosNfe.Dest semEnd = new DadosNfe.Dest("00000000000191", "X LTDA", "ISENTO", null, null, "", "", null, "", "", "", "", "");
        List<Validador.Problema> pe = Validador.nfe(c, nota(semEnd, false, null, null, null, "15"));
        confere(tem(pe, "endereço completo") && tem(pe, "UF") && tem(pe, "IBGE") && tem(pe, "CEP"), "endereço do destinatário incompleto lista tudo");
        confere(tem(Validador.nfe(c, new DadosNfe(21, mercado("RS", "0170123456"), List.of(maca(), cebola()), List.of(new Dados.Pag("15", d("135.00"), null, null, null)), null, false, 9, d("5.00"), null, "VENDA", null, 1)), "modalidade é 9"), "frete com modalidade 'sem frete'");
        confere(tem(Validador.nfe(c, nota(mercado("RS", "0170123456"), false, null, d("500"), null, "15")), "desconto"), "desconto maior que os produtos");
        DadosNfe errado = new DadosNfe(21, mercado("RS", "0170123456"), List.of(maca(), cebola()), List.of(new Dados.Pag("15", d("100.00"), null, null, null)), null, false, 9, null, null, "VENDA", null, 1);
        confere(tem(Validador.nfe(c, errado), "soma dos pagamentos"), "pagamento diferente do total");
        List<DadosNfe.Parcela> ruim = List.of(new DadosNfe.Parcela(LocalDate.now().minusDays(1), d("100.00")));
        pe = Validador.nfe(c, nota(mercado("RS", "0170123456"), false, null, null, ruim, "15"));
        confere(tem(pe, "anterior à data") && tem(pe, "soma das duplicatas"), "duplicata vencida e soma errada");
        confere(tem(Validador.nfe(c, nota(new DadosNfe.Dest("52998224725", "Joao", null, null, null, "R", "1", null, "B", "4314902", "POA", "RS", "90010000"), false, null, null, null, "01")), "consumidor final"), "pessoa física compra como consumidor final");

        System.out.println("Assinatura, autorização e eventos");
        Path tmp = Files.createTempDirectory("nfe"); Path pfx = tmp.resolve("a1.pfx"); FiscalTeste.cert = pfx.toString();
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "loja", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12", "-keystore", pfx.toString(), "-storepass", "senha123", "-dname", "CN=FRUTEIRA TESTE:12345678000195").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou");
        c.certPath = pfx.toString(); c.certSenha = "senha123";
        AtomicReference<String> resposta = new AtomicReference<>(); List<String> recebidos = new ArrayList<>(); List<String> caminhos = new ArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            recebidos.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); caminhos.add(ex.getRequestURI().getPath());
            String r = resposta.get(); if (r == null) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            byte[] b = r.getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start(); String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        c.urlNfeAutorizacao = base + "/nfe-aut"; c.urlNfeConsulta = base + "/nfe-cons"; c.urlNfeEvento = base + "/nfe-evt"; c.urlAutorizacao = base + "/nfce-aut"; c.urlConsulta = base + "/nfce-cons"; c.urlEvento = base + "/nfce-evt";
        NfeEmissor em = new NfeEmissor(c, new SefazClient(c), Assinador.carregar(pfx.toString(), "senha123"));
        DadosNfe n = nota(mercado("RS", "0170123456"), false, null, null, null, "15");

        resposta.set(FiscalTeste.retorno(100, "Autorizado o uso da NF-e", "143260000000321"));
        Resultado r = em.emitir(n);
        confere("AUTORIZADA".equals(r.status) && "143260000000321".equals(r.protocolo) && r.xmlAutorizado.contains("<nfeProc") && r.xmlAutorizado.contains("<protNFe"), "cStat 100 → AUTORIZADA com nfeProc");
        confere(caminhos.get(0).equals("/nfe-aut") && recebidos.get(0).contains("<indSinc>1</indSinc>") && recebidos.get(0).contains("<mod>55</mod>"), "enviado ao webservice da NF-e (não ao da NFC-e)");
        confere(FiscalTeste.assinaturaValida(r.xmlAssinado) && !r.xmlAssinado.contains("infNFeSupl"), "assinatura XMLDSig válida, sem infNFeSupl");
        resposta.set(FiscalTeste.retorno(539, "Rejeicao: Duplicidade de NF-e", null));
        resposta.set(FiscalTeste.retorno(215, "Rejeicao: Falha no schema XML", null));
        r = em.emitir(n); confere("REJEITADA".equals(r.status) && r.cStat == 215, "rejeição da SEFAZ → REJEITADA");
        resposta.set(FiscalTeste.retorno(302, "Uso Denegado: Irregularidade fiscal do destinatario", null));
        r = em.emitir(n); confere("DENEGADA".equals(r.status), "302 → DENEGADA");
        resposta.set(null);
        r = em.emitir(n);
        confere("PENDENTE".equals(r.status) && r.semComunicacao && r.xmlAssinado != null && FiscalTeste.assinaturaValida(r.xmlAssinado), "SEFAZ fora do ar → PENDENTE (já assinada, sem perder o número)");
        String xmlPend = r.xmlAssinado, chPend = r.chave;
        resposta.set(FiscalTeste.retorno(100, "Autorizado o uso da NF-e", "143260000000322"));
        r = em.transmitir(xmlPend, chPend, 21);
        confere("AUTORIZADA".equals(r.status) && "143260000000322".equals(r.protocolo), "reenvio da nota pendente → AUTORIZADA");
        String chOk = r.chave;
        resposta.set("<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\"><cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo><retEvento><infEvento><cStat>135</cStat><xMotivo>Evento registrado e vinculado a NF-e</xMotivo><nProt>143260000000900</nProt></infEvento></retEvento></retEnvEvento>");
        Resultado cn = em.cancelar(chOk, "143260000000322", "Cancelamento por erro no pedido");
        confere("OK".equals(cn.status) && cn.cStat == 135 && recebidos.get(recebidos.size() - 1).contains("<tpEvento>110111</tpEvento>") && caminhos.get(caminhos.size() - 1).equals("/nfe-evt"), "cancelamento 110111 pelo webservice de eventos da NF-e");
        Resultado cc = em.cartaCorrecao(chOk, 1, "Correcao do endereco de entrega no complemento");
        String ev = recebidos.get(recebidos.size() - 1);
        confere("OK".equals(cc.status) && ev.contains("<tpEvento>110110</tpEvento>") && ev.contains("<descEvento>Carta de Correcao</descEvento>") && ev.contains("<xCondUso>") && ev.contains("ID110110" + chOk + "01") && ev.contains("<Signature"), "carta de correção 110110 assinada, com condição de uso");
        boolean curta = false; try { em.cancelar(chOk, "1", "curta"); } catch (IllegalArgumentException e) { curta = true; }
        boolean cartaCurta = false; try { em.cartaCorrecao(chOk, 1, "curta"); } catch (IllegalArgumentException e) { cartaCurta = true; }
        confere(curta && cartaCurta, "justificativa e texto curtos são recusados");
        srv.stop(0);
        System.out.println("\n" + ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }
    static String xmlDe(Config c, DadosNfe n) { return NfeXml.montar(c, n, NfeXml.chave(c, NfceXml.agora(), 21, "12345678"), NfceXml.agora(), "12345678"); }
}
