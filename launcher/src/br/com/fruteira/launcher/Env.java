package br.com.fruteira.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;

/** Arquivo .env: leitura (CHAVE=valor, # comenta), gravação preservando comentários e geração de senhas. Só JDK. */
final class Env {
    final Path arquivo;
    final List<String> linhas = new ArrayList<>();
    private static final Pattern ATIVA = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    Env(Path arquivo) throws IOException {
        this.arquivo = arquivo;
        if (Files.exists(arquivo)) for (String l : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) linhas.add(l.replace("\r", ""));
    }

    static String limpa(String v) {
        v = v.strip();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) v = v.substring(1, v.length() - 1);
        return v;
    }

    /** Valor da última atribuição ativa; "" se não houver. */
    String get(String chave) {
        String r = "";
        for (String l : linhas) { Matcher m = ATIVA.matcher(l); if (m.matches() && m.group(1).equals(chave)) r = limpa(m.group(2)); }
        return r;
    }

    String getOrDefault(String chave, String padrao) { String v = get(chave); return v.isBlank() ? padrao : v; }

    Map<String, String> mapa() {
        Map<String, String> r = new LinkedHashMap<>();
        for (String l : linhas) { Matcher m = ATIVA.matcher(l); if (m.matches()) r.put(m.group(1), limpa(m.group(2))); }
        return r;
    }

    /** Troca a linha ativa; senão descomenta "#CHAVE=" (a primeira); senão acrescenta no fim. */
    void set(String chave, String valor) {
        int ativa = -1, comentada = -1;
        for (int i = 0; i < linhas.size(); i++) {
            String l = linhas.get(i);
            if (l.startsWith(chave + "=")) ativa = i;
            else if (comentada < 0 && l.startsWith("#" + chave + "=")) comentada = i;
        }
        if (ativa >= 0) linhas.set(ativa, chave + "=" + valor);
        else if (comentada >= 0) linhas.set(comentada, chave + "=" + valor);
        else { if (!linhas.isEmpty() && !linhas.get(linhas.size() - 1).isBlank()) linhas.add(""); linhas.add(chave + "=" + valor); }
    }

    void salvar() throws IOException {
        Files.write(arquivo, (String.join("\n", linhas) + "\n").getBytes(StandardCharsets.UTF_8));
        try { Files.setPosixFilePermissions(arquivo, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignorado) { /* Windows */ }
    }

    static String senha(int n) {
        String c = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"; SecureRandom r = new SecureRandom();
        StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) s.append(c.charAt(r.nextInt(c.length()))); return s.toString();
    }

    static boolean exemplo(String v) { return v.isBlank() || v.startsWith("troque"); }

    /** Senhas vazias ou de exemplo ("troque-...") viram senhas aleatórias gravadas no arquivo. Devolve as chaves geradas. */
    List<String> gerarSenhas(String... chaves) throws IOException {
        List<String> feitas = new ArrayList<>();
        for (String k : chaves) if (exemplo(get(k))) { set(k, senha(k.equals("TEF_TOKEN") ? 40 : k.equals("JWT_SEGREDO") ? 48 : k.equals("ADMIN_SENHA_INICIAL") ? 14 : 24)); feitas.add(k); }
        if (!feitas.isEmpty()) salvar();
        return feitas;
    }
}
