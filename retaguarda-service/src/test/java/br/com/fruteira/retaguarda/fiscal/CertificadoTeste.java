package br.com.fruteira.retaguarda.fiscal;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.w3c.dom.*;

/** Certificado A1 (certificado.pfx na raiz), conferência de validade/CNPJ, QR Code, SOAP e assinatura adulterada: ./testar-fiscal.sh */
public class CertificadoTeste {
    static int ok = 0, falhas = 0;
    static void confere(boolean c, String nome) { if (c) { ok++; System.out.println("  ✔ " + nome); } else { falhas++; System.out.println("  ✘ FALHOU: " + nome); } }

    static void gerar(Path pfx, String cn, String... extra) throws Exception {
        var cmd = new java.util.ArrayList<>(java.util.List.of("keytool", "-genkeypair", "-alias", "loja", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", pfx.toString(), "-storepass", "senha123", "-dname", "CN=" + cn));
        cmd.addAll(java.util.List.of(extra));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou");
    }

    public static void main(String[] a) throws Exception {
        Path raiz = Files.createTempDirectory("raiz"); Path fundo = Files.createDirectories(raiz.resolve("retaguarda-service/x"));
        gerar(raiz.resolve("certificado.pfx"), "FRUTEIRA TESTE:12345678000195", "-validity", "365");
        String antes = System.getProperty("user.dir");

        System.out.println("certificado.pfx na raiz do projeto");
        System.setProperty("user.dir", fundo.toString());
        var f = Certificado.resolver("");
        confere(f != null && f.toPath().equals(raiz.resolve("certificado.pfx")), "FISCAL_CERT_PATH vazio acha certificado.pfx subindo as pastas");
        confere(Certificado.resolver("certificado.pfx") != null, "caminho relativo também é procurado a partir da raiz");
        confere(Certificado.resolver(raiz.resolve("certificado.pfx").toString()) != null, "caminho absoluto");
        confere(Certificado.resolver("nao-existe.pfx") == null && Certificado.resolver("/tmp/nao-existe.pfx") == null, "arquivo inexistente → null");
        var k = Assinador.carregar("", "senha123");
        confere(k.arquivo().endsWith("certificado.pfx"), "Assinador.carregar usa o certificado da raiz");
        System.setProperty("user.dir", antes);

        System.out.println("Erros de leitura em português");
        try { Assinador.carregar(raiz.resolve("certificado.pfx").toString(), "errada"); confere(false, "senha errada"); }
        catch (IllegalStateException e) { confere(e.getMessage().contains("Senha do certificado A1 incorreta"), "senha errada: " + e.getMessage()); }
        Path lixo = raiz.resolve("lixo.pfx"); Files.writeString(lixo, "isto não é um pfx");
        try { Assinador.carregar(lixo.toString(), "x"); confere(false, "arquivo inválido"); }
        catch (IllegalStateException e) { confere(e.getMessage().contains("inválido"), "arquivo que não é PKCS#12: " + e.getMessage().substring(0, 40) + "…"); }
        try { Assinador.carregar(raiz.resolve("nada.pfx").toString(), "x"); confere(false, "ausente"); }
        catch (IllegalStateException e) { confere(e.getMessage().contains("não encontrado"), "arquivo ausente: pede o certificado.pfx na raiz"); }

        System.out.println("Conferência do certificado");
        var agora = Instant.now();
        var i = Certificado.inspecionar(k, "12.345.678/0001-95", "certificado.pfx", agora);
        confere(i.ok() && "12345678000195".equals(i.cnpj()) && i.diasRestantes() > 300 && i.avisos().isEmpty(), "A1 válido, CNPJ lido do CN, sem avisos");
        var filial = Certificado.inspecionar(k, "12.345.678/0002-76", "x", agora);
        confere(filial.ok(), "filial (mesma raiz de 8 dígitos) é aceita");
        var outro = Certificado.inspecionar(k, "11.222.333/0001-81", "x", agora);
        confere(!outro.ok() && outro.problemas().get(0).contains("não é da mesma empresa"), "CNPJ de outra empresa é recusado");
        var vencido = Certificado.inspecionar(k, "12345678000195", "x", agora.plusSeconds(400L * 86400));
        confere(!vencido.ok() && vencido.problemas().get(0).contains("VENCIDO"), "certificado vencido é recusado");
        var perto = Certificado.inspecionar(k, "12345678000195", "x", agora.plusSeconds(350L * 86400));
        confere(perto.ok() && perto.avisos().size() == 1 && perto.avisos().get(0).contains("vence em"), "vence em ≤ 30 dias: só aviso");
        var futuro = Certificado.inspecionar(k, "12345678000195", "x", agora.minusSeconds(5L * 86400));
        confere(!futuro.ok() && futuro.problemas().get(0).contains("ainda não é válido"), "ainda não vigente é recusado");
        gerar(raiz.resolve("semcnpj.pfx"), "LOJA SEM CNPJ", "-validity", "365");
        var sem = Certificado.inspecionar(Assinador.carregar(raiz.resolve("semcnpj.pfx").toString(), "senha123"), "12345678000195", "x", Instant.now().plusSeconds(60));
        confere(sem.ok() && sem.avisos().stream().anyMatch(s -> s.contains("CNPJ do certificado")), "sem CNPJ legível: aviso (não bloqueia)");

        System.out.println("QR Code: identificador do CSC sem zeros");
        Config c = new Config(); c.tpAmb = 2; c.cscId = "000001"; c.csc = "CSC"; c.urlQr = "http://qr";
        String q = NfceXml.qrOnline(c, "4".repeat(44));
        confere(q.contains("|2|2|1|") && !q.contains("|000001|"), "000001 vai como 1 no QR Code");
        c.cscId = "000012"; confere(NfceXml.qrOnline(c, "4".repeat(44)).contains("|2|2|12|"), "000012 → 12");

        System.out.println("cNF aceito pela SEFAZ");
        confere(NfceXml.cnfInvalido("00000007", 7) && NfceXml.cnfInvalido("11111111", 5) && NfceXml.cnfInvalido("12345678", 5) && NfceXml.cnfInvalido("87654321", 5) && !NfceXml.cnfInvalido("48213907", 7), "recusa cNF = nNF, repetido e sequência");
        boolean todosOk = true; for (int n = 0; n < 2000; n++) { String x = NfceXml.novoCNF(n); todosOk &= x.matches("\\d{8}") && !NfceXml.cnfInvalido(x, n); }
        confere(todosOk, "novoCNF(numero) sempre devolve 8 dígitos válidos");

        System.out.println("SOAP 1.2 e status do serviço");
        AtomicReference<String> tipo = new AtomicReference<>(), versao = new AtomicReference<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            tipo.set(ex.getRequestHeaders().getFirst("Content-Type")); versao.set(ex.getProtocol()); ex.getRequestBody().readAllBytes();
            byte[] r = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"><soap:Body><retConsStatServ xmlns=\"http://www.portalfiscal.inf.br/nfe\"><cStat>107</cStat><xMotivo>Servico em Operacao</xMotivo></retConsStatServ></soap:Body></soap:Envelope>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/soap+xml"); ex.sendResponseHeaders(200, r.length); ex.getResponseBody().write(r); ex.close();
        });
        srv.start();
        Config s = new Config(); s.tpAmb = 2; s.certPath = raiz.resolve("certificado.pfx").toString(); s.certSenha = "senha123"; s.timeoutSeg = 3;
        String base = "http://127.0.0.1:" + srv.getAddress().getPort(); s.urlStatus = base + "/st"; s.urlNfeStatus = base + "/nst";
        var ret = new SefazClient(s).statusServico(false);
        confere(ret.cStat() == 107 && "Servico em Operacao".equals(ret.xMotivo()), "consStatServ → 107 Serviço em Operação");
        confere(tipo.get().startsWith("application/soap+xml") && tipo.get().contains("action=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4/nfeStatusServicoNF\""), "Content-Type SOAP 1.2 com action: " + tipo.get());
        confere("HTTP/1.1".equals(versao.get()), "HTTP/1.1 (sem upgrade h2c)");
        new SefazClient(s).statusServico(true); confere(true, "NF-e usa a própria URL de status");
        srv.stop(0);

        System.out.println("Assinatura adulterada é detectada");
        Config e = new Config(); e.tpAmb = 2; e.cnpj = "12345678000195"; e.razao = "X"; e.ie = "0170123456"; e.logradouro = "R"; e.bairro = "B"; e.cMun = "4321105"; e.municipio = "C"; e.cep = "95865000";
        String xml = "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe Id=\"NFe1\" versao=\"4.00\"><total>10.00</total></infNFe></NFe>";
        Document d = SefazClient.parse(xml); Element nfe = d.getDocumentElement(), inf = (Element) nfe.getElementsByTagNameNS("*", "infNFe").item(0);
        Assinador.assinar(d, nfe, inf, k);
        confere(Assinador.verificar(d, inf, k), "assinatura recém-gerada confere");
        d.getElementsByTagNameNS("*", "total").item(0).setTextContent("1.00");
        confere(!Assinador.verificar(d, inf, k), "alterar o conteúdo depois de assinar invalida");

        System.out.println("\n" + ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }
}
