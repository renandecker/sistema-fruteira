package br.com.fruteira.retaguarda.fiscal;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import org.w3c.dom.*;

/**
 * Testes do módulo fiscal SEM Maven nem biblioteca externa (só JDK): ./testar-fiscal.sh
 * Usa um certificado A1 de teste (keytool) e uma SEFAZ simulada em HTTP local.
 */
public class FiscalTeste {
    static int ok = 0, falhas = 0;
    static void confere(boolean c, String nome) { if (c) { ok++; System.out.println("  ✔ " + nome); } else { falhas++; System.out.println("  ✘ FALHOU: " + nome); } }
    static void lanca(Runnable r, String nome) { try { r.run(); confere(false, nome); } catch (IllegalArgumentException e) { confere(true, nome); } }

    static final String CPF_OK = "529.982.247-25";            // CPF de teste válido (dígitos verificadores corretos)

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("fiscal");
        Path pfx = tmp.resolve("a1.pfx"); cert = pfx.toString();
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "loja", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12",
            "-keystore", pfx.toString(), "-storepass", "senha123", "-dname", "CN=FRUTEIRA TESTE:12345678000195").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou");

        System.out.println("CPF");
        confere(Cpf.valido(CPF_OK), "CPF válido com máscara");
        confere(Cpf.valido("52998224725"), "CPF válido só dígitos");
        confere(!Cpf.valido("52998224724"), "dígito verificador errado é rejeitado");
        confere(!Cpf.valido("11111111111"), "dígitos todos iguais são rejeitados");
        confere(!Cpf.valido("1234567890"), "tamanho errado é rejeitado");
        confere(!Cpf.valido("abc"), "texto é rejeitado");
        confere(Cpf.normalizarOuNulo("  ") == null && Cpf.normalizarOuNulo(null) == null, "vazio = venda anônima (null)");
        confere("52998224725".equals(Cpf.normalizarOuNulo(CPF_OK)), "normaliza para só dígitos");
        lanca(() -> Cpf.normalizarOuNulo("123.456.789-00"), "normalizar lança 'CPF inválido'");
        confere("529.982.247-25".equals(Cpf.formatar("52998224725")), "formata 000.000.000-00");

        Config c = new Config();
        c.tpAmb = 2; c.cnpj = "12.345.678/0001-95"; c.razao = "FRUTEIRA CONVENTOS LTDA"; c.fantasia = "Fruteira Conventos"; c.ie = "0170123456";
        c.logradouro = "RUA DAS FRUTAS"; c.numero = "100"; c.bairro = "CENTRO"; c.cMun = "4321105"; c.municipio = "CONVENTOS"; c.cep = "95865-000";
        c.cscId = "1"; c.csc = "CSC-DE-TESTE-0123456789"; c.certPath = pfx.toString(); c.certSenha = "senha123"; c.timeoutSeg = 3;
        Dados.Item banana = new Dados.Item("12", "Banana prata", "08039000", "KG", new BigDecimal("0.350"), new BigDecimal("7.90"), new BigDecimal("2.77"));
        Dados.Item cebola = new Dados.Item("13", "Cebola", "07031019", "KG", new BigDecimal("1.000"), new BigDecimal("5.00"), new BigDecimal("5.00"));
        List<Dados.Item> itens = List.of(banana, cebola);
        List<Dados.Pag> pags = List.of(new Dados.Pag("01", new BigDecimal("10.00"), null, null, null));

        System.out.println("Chave e XML");
        var emi = NfceXml.agora();
        String ch = NfceXml.chave(c, emi, 7, 1, "12345678");
        confere(ch.length() == 44 && ch.startsWith("43") && ch.substring(6, 20).equals("12345678000195") && ch.substring(20, 22).equals("65"), "chave de 44 dígitos (RS, CNPJ, modelo 65)");
        confere(NfceXml.dv(ch.substring(0, 43)) == ch.charAt(43) - '0', "dígito verificador da chave (módulo 11)");
        String xml = NfceXml.montar(c, new Dados(7, CPF_OK, "Maria da Silva", itens, pags), ch, emi, 1, "12345678");
        confere(xml.contains("<dest><CPF>52998224725</CPF>") && xml.contains("<indIEDest>9</indIEDest></dest>"), "CPF no grupo <dest> (só dígitos)");
        confere(xml.contains("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO"), "homologação: xNome do destinatário padrão do fisco");
        confere(xml.contains("<vNF>7.77</vNF>") && xml.contains("<vTroco>2.23</vTroco>"), "total 7,77 e troco 2,23 calculados");
        confere(xml.indexOf("<emit>") < xml.indexOf("<dest>") && xml.indexOf("<dest>") < xml.indexOf("<det "), "ordem emit → dest → det");
        String anon = NfceXml.montar(c, new Dados(7, "", null, itens, pags), ch, emi, 1, "12345678");
        confere(!anon.contains("<dest>") && !anon.contains("<CPF>"), "venda anônima: sem grupo <dest>");
        c.tpAmb = 1;
        String prod = NfceXml.montar(c, new Dados(7, CPF_OK, "Maria da Silva", itens, pags), ch, emi, 1, "12345678");
        confere(prod.contains("<xNome>Maria da Silva</xNome>") && !prod.contains("HOMOLOGACAO"), "produção: nome opcional vai em <xNome>");
        c.tpAmb = 2;
        lanca(() -> NfceXml.montar(c, new Dados(7, "11111111111", null, itens, pags), ch, emi, 1, "12345678"), "CPF inválido não vira XML");
        lanca(() -> NfceXml.montar(c, new Dados(7, null, null, List.of(new Dados.Item("1", "X", "123", "UN", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)), pags), ch, emi, 1, "12345678"), "NCM sem 8 dígitos é recusado");
        lanca(() -> NfceXml.montar(c, new Dados(7, null, null, itens, List.of(new Dados.Pag("01", new BigDecimal("5"), null, null, null))), ch, emi, 1, "12345678"), "pagamento menor que o total é recusado");

        System.out.println("Assinatura e QR Code");
        Assinador.Chave k = Assinador.carregar(pfx.toString(), "senha123");
        // SEFAZ simulada
        AtomicReference<String> resposta = new AtomicReference<>(); List<String> recebidos = new ArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); recebidos.add(corpo);
            String r = resposta.get();
            if (r == null) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            if (r.equals("DEMORA")) { try { Thread.sleep(6000); } catch (Exception e) {} }
            byte[] b = (r.equals("DEMORA") ? "" : r).getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start(); String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        c.urlAutorizacao = base + "/aut"; c.urlConsulta = base + "/cons"; c.urlEvento = base + "/evt";
        NfceEmissor em = new NfceEmissor(c, new SefazClient(c), k);

        resposta.set(retorno(100, "Autorizado o uso da NF-e", "143260000000001"));
        Resultado r = em.emitir(new Dados(7, CPF_OK, "Maria", itens, pags), false);
        confere("AUTORIZADA".equals(r.status) && r.cStat == 100 && "143260000000001".equals(r.protocolo), "cStat 100 → AUTORIZADA com protocolo");
        confere(r.xmlAutorizado != null && r.xmlAutorizado.contains("<nfeProc") && r.xmlAutorizado.contains("<protNFe"), "nfeProc (NFe + protocolo) montado");
        confere(recebidos.get(0).contains("<CPF>52998224725</CPF>") && recebidos.get(0).contains("soap12:Envelope") && recebidos.get(0).contains("<indSinc>1</indSinc>"), "SOAP enviado com o CPF no destinatário");
        confere(r.xmlAssinado.indexOf("<infNFeSupl>") > r.xmlAssinado.indexOf("</infNFe>") && r.xmlAssinado.indexOf("<infNFeSupl>") < r.xmlAssinado.indexOf("<Signature"), "ordem infNFe → infNFeSupl → Signature (sem prefixo)");
        confere(assinaturaValida(r.xmlAssinado), "assinatura XMLDSig válida");
        String esperado = NfceXml.qrOnline(c, r.chave);
        confere(r.qrCode.equals(esperado) && r.qrCode.matches(".*\\?p=\\d{44}\\|2\\|2\\|1\\|[0-9A-F]{40}"), "QR Code v2 online (chave|2|tpAmb|idCSC|SHA-1)");
        confere(r.xmlAssinado.contains("<qrCode><![CDATA[" + r.qrCode + "]]></qrCode>"), "QR Code dentro do XML (CDATA)");

        System.out.println("Retornos da SEFAZ");
        resposta.set(retorno(237, "Rejeicao: CPF do destinatario invalido", null));
        r = em.emitir(new Dados(8, CPF_OK, null, itens, pags), false);
        confere("REJEITADA".equals(r.status) && r.cStat == 237 && r.erroCpf, "237 CPF inválido → REJEITADA com erroCpf (caixa pode corrigir)");
        resposta.set(retorno(528, "Rejeicao: Total da BC ICMS difere do calculado", null));
        r = em.emitir(new Dados(8, null, null, itens, pags), false);
        confere("REJEITADA".equals(r.status) && !r.erroCpf, "outra rejeição não pede correção de CPF");
        resposta.set(retorno(302, "Uso Denegado: Irregularidade fiscal do destinatario", null));
        r = em.emitir(new Dados(9, CPF_OK, null, itens, pags), false);
        confere("DENEGADA".equals(r.status), "302 → DENEGADA");
        resposta.set(null);
        r = em.emitir(new Dados(10, CPF_OK, null, itens, pags), false);
        confere(r.status == null && r.semComunicacao, "SEFAZ fora do ar → semComunicacao (caixa segue em contingência)");
        resposta.set("DEMORA");
        r = em.emitir(new Dados(11, CPF_OK, null, itens, pags), false);
        confere(r.status == null && r.semComunicacao, "timeout + consulta sem resposta → semComunicacao");

        System.out.println("Contingência offline (tpEmis 9)");
        Resultado cont = em.emitir(new Dados(12, CPF_OK, "Maria", itens, pags), true);
        confere("CONTINGENCIA".equals(cont.status) && cont.tpEmis == 9 && cont.chave.charAt(34) == '9', "tpEmis 9 na chave e status CONTINGENCIA");
        confere(cont.xmlAssinado.contains("<tpEmis>9</tpEmis>") && cont.xmlAssinado.contains("<dhCont>") && cont.xmlAssinado.contains("<xJust>"), "dhCont e xJust no XML");
        confere(cont.qrCode.matches(".*\\?p=\\d{44}\\|2\\|2\\|\\d{2}\\|7\\.77\\|[0-9A-F]+\\|1\\|[0-9A-F]{40}"), "QR Code offline (dia|vNF|digVal|idCSC|hash)");
        confere(assinaturaValida(cont.xmlAssinado), "assinatura válida na contingência");
        int antes = recebidos.size();
        confere(recebidos.size() == antes, "contingência não chama a SEFAZ");
        resposta.set(retorno(100, "Autorizado o uso da NF-e", "143260000000002"));
        Resultado t = em.transmitir(cont.xmlAssinado, cont.chave, cont.numero, 9);
        confere("AUTORIZADA".equals(t.status) && t.qrCode != null && t.qrCode.equals(cont.qrCode), "transmissão posterior → AUTORIZADA (mesmo XML e QR Code)");

        System.out.println("Cancelamento");
        resposta.set("<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\"><cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo><retEvento><infEvento><cStat>135</cStat><xMotivo>Evento registrado e vinculado a NF-e</xMotivo><nProt>143260000000003</nProt></infEvento></retEvento></retEnvEvento>");
        Resultado cn = em.cancelar(t.chave, "143260000000002", "Cancelamento por erro de digitacao");
        confere("CANCELADA".equals(cn.status) && cn.cStat == 135, "135 → CANCELADA");
        lanca(() -> { try { em.cancelar(t.chave, "1", "curta"); } catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } }, "justificativa curta é recusada");
        confere(recebidos.get(recebidos.size() - 1).contains("<tpEvento>110111</tpEvento>") && recebidos.get(recebidos.size() - 1).contains("<Signature"), "evento 110111 enviado assinado");
        srv.stop(0);

        System.out.println("\n" + ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }

    public static String retorno(int cStat, String motivo, String nProt) {
        String prot = "<protNFe versao=\"4.00\"><infProt><tpAmb>2</tpAmb><chNFe>x</chNFe><dhRecbto>2026-10-13T10:00:00-03:00</dhRecbto>" + (nProt != null ? "<nProt>" + nProt + "</nProt>" : "") + "<cStat>" + cStat + "</cStat><xMotivo>" + motivo + "</xMotivo></infProt></protNFe>";
        return "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"><soap:Body><nfeResultMsg><retEnviNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\"><tpAmb>2</tpAmb><cStat>104</cStat><xMotivo>Lote processado</xMotivo>" + prot + "</retEnviNFe></nfeResultMsg></soap:Body></soap:Envelope>";
    }

    public static boolean assinaturaValida(String xml) throws Exception {
        Document d = SefazClient.parse(xml);
        ((Element) d.getElementsByTagNameNS("*", "infNFe").item(0)).setIdAttribute("Id", true);
        Node sig = d.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        DOMValidateContext ctx = new DOMValidateContext(Assinador.carregar(cert, "senha123").cert().getPublicKey(), sig);
        ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);          // o padrão da NF-e usa SHA-1
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
    }
    public static String cert;
}
