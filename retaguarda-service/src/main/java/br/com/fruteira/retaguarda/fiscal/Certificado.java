package br.com.fruteira.retaguarda.fiscal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Localização e conferência do certificado A1 (.pfx) do emitente. Só JDK. */
public final class Certificado {
    private Certificado() {}
    public static final String PADRAO = "certificado.pfx";

    /**
     * Acha o arquivo do certificado. Vazio = {@code certificado.pfx} na raiz do projeto (pasta atual ou até 3 níveis acima, ou FRUTEIRA_HOME);
     * caminho relativo também é procurado a partir dessas pastas. Devolve null se não achar.
     */
    public static File resolver(String caminho) {
        String c = caminho == null || caminho.isBlank() ? PADRAO : caminho.trim();
        File f = new File(c);
        if (f.isAbsolute()) return f.canRead() ? f : null;
        List<File> raizes = new ArrayList<>();
        String home = System.getenv("FRUTEIRA_HOME"); if (home != null && !home.isBlank()) raizes.add(new File(home));
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 4 && d != null; i++, d = d.getParentFile()) raizes.add(d);
        for (File r : raizes) { File x = new File(r, c); if (x.isFile() && x.canRead()) return x; }
        return null;
    }

    /** O que se sabe do certificado, e o que impede (problemas) ou merece atenção (avisos). */
    public record Info(String titular, String cnpj, String validoDe, String validoAte, long diasRestantes, String arquivo, List<String> problemas, List<String> avisos) {
        public boolean ok() { return problemas.isEmpty(); }
    }

    /** CNPJ (14 dígitos) do titular ICP-Brasil: OID 2.16.76.1.3.3 no SubjectAltName, ou "NOME:CNPJ" no CN. Vazio se não achar. */
    static String cnpjDoCertificado(X509Certificate c) {
        byte[] oid = {0x60, 0x4C, 0x01, 0x03, 0x03};                       // 2.16.76.1.3.3 (e-CNPJ)
        try {
            var sans = c.getSubjectAlternativeNames();
            if (sans != null) for (List<?> e : sans) {
                if (!(e.get(0) instanceof Integer t) || t != 0 || !(e.get(1) instanceof byte[] der)) continue;
                int i = indice(der, oid); if (i < 0) continue;
                Matcher m = Pattern.compile("\\d{14}").matcher(new String(der, i + oid.length, der.length - i - oid.length, StandardCharsets.ISO_8859_1));
                if (m.find()) return m.group();
            }
        } catch (Exception ignorado) { /* cai no CN */ }
        Matcher m = Pattern.compile(":\\s*(\\d{14})\\b").matcher(c.getSubjectX500Principal().getName());
        return m.find() ? m.group(1) : "";
    }
    private static int indice(byte[] a, byte[] b) {
        for (int i = 0; i + b.length <= a.length; i++) { int j = 0; while (j < b.length && a[i + j] == b[j]) j++; if (j == b.length) return i; }
        return -1;
    }
    static String cn(X509Certificate c) {
        for (String p : c.getSubjectX500Principal().getName().split("(?<!\\\\),")) if (p.startsWith("CN=")) return p.substring(3);
        return c.getSubjectX500Principal().getName();
    }

    /** Confere validade, uso da chave e se o CNPJ do certificado é da mesma empresa (raiz de 8 dígitos) do emitente. */
    public static Info inspecionar(Assinador.Chave chave, String cnpjEmitente, String arquivo, Instant agora) {
        X509Certificate c = chave.cert();
        List<String> prob = new ArrayList<>(), av = new ArrayList<>();
        long dias = Duration.between(agora, c.getNotAfter().toInstant()).toDays();
        if (agora.isAfter(c.getNotAfter().toInstant())) prob.add("certificado A1 VENCIDO em " + c.getNotAfter().toInstant().toString().substring(0, 10) + ": renove com a certificadora");
        else if (agora.isBefore(c.getNotBefore().toInstant())) prob.add("certificado A1 ainda não é válido (começa em " + c.getNotBefore().toInstant().toString().substring(0, 10) + ")");
        else if (dias <= 30) av.add("certificado A1 vence em " + dias + " dia(s): renove antes para não parar de emitir notas");
        boolean[] ku = c.getKeyUsage();
        if (ku != null && !ku[0] && !ku[1]) prob.add("o certificado não permite assinatura digital (use um e-CNPJ A1 de assinatura)");
        String cnpj = cnpjDoCertificado(c), emit = cnpjEmitente == null ? "" : cnpjEmitente.replaceAll("\\D", "");
        if (cnpj.isEmpty()) av.add("não foi possível ler o CNPJ do certificado (confira se é e-CNPJ da empresa)");
        else if (emit.length() == 14 && !cnpj.substring(0, 8).equals(emit.substring(0, 8)))
            prob.add("o CNPJ do certificado (" + cnpj + ") não é da mesma empresa do emitente (FISCAL_CNPJ " + emit + "): a SEFAZ rejeita (cStat 213/280)");
        return new Info(cn(c), cnpj, c.getNotBefore().toInstant().toString().substring(0, 10), c.getNotAfter().toInstant().toString().substring(0, 10), dias, arquivo, prob, av);
    }
}
