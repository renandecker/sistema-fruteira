package br.com.fruteira.retaguarda;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Senhas com PBKDF2-SHA256 e sal por usuário ("pbkdf2$iterações$sal$hash"). Reconhece o SHA-256 antigo (hex) e pede a atualização no próximo login. Só JDK. */
final class Senhas {
    private Senhas() {}
    static final int ITERACOES = 120_000;

    static String gerar(String senha) {
        byte[] sal = new byte[16]; new SecureRandom().nextBytes(sal);
        return "pbkdf2$" + ITERACOES + "$" + Base64.getEncoder().encodeToString(sal) + "$" + Base64.getEncoder().encodeToString(pbkdf2(senha, sal, ITERACOES));
    }

    static boolean confere(String senha, String guardado) {
        if (senha == null || guardado == null || guardado.isBlank()) return false;
        try {
            if (guardado.startsWith("pbkdf2$")) {
                String[] p = guardado.split("\\$");
                if (p.length != 4) return false;
                return MessageDigest.isEqual(Base64.getDecoder().decode(p[3]), pbkdf2(senha, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
            }
            return MessageDigest.isEqual(legado(senha).getBytes(StandardCharsets.UTF_8), guardado.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) { return false; }
    }

    static boolean precisaAtualizar(String guardado) { return guardado != null && !guardado.startsWith("pbkdf2$"); }

    /** Formato antigo (SHA-256 sem sal): só para reconhecer bancos já existentes. */
    static String legado(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("fruteira:" + s).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] pbkdf2(String senha, byte[] sal, int iteracoes) {
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(senha.toCharArray(), sal, iteracoes, 256)).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
