package br.com.fruteira.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Backup e restauração só com Java + PostgreSQL: pg_dump dos dois bancos + .env + certificado A1 + realm, em um .zip (opcionalmente criptografado AES-256-GCM). */
final class Backup {
    private Backup() {}
    private static final byte[] MAGIA = "FRBK1".getBytes(StandardCharsets.US_ASCII);

    static byte[] chave(String senha, byte[] sal) throws GeneralSecurityException {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(senha.toCharArray(), sal, 200_000, 256)).getEncoded();
    }
    static void criptografar(Path origem, Path destino, String senha) throws Exception {
        SecureRandom r = new SecureRandom(); byte[] sal = new byte[16], iv = new byte[12]; r.nextBytes(sal); r.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(chave(senha, sal), "AES"), new GCMParameterSpec(128, iv));
        try (var out = new BufferedOutputStream(Files.newOutputStream(destino)); var in = Files.newInputStream(origem)) {
            out.write(MAGIA); out.write(sal); out.write(iv);
            try (var co = new CipherOutputStream(out, c)) { in.transferTo(co); }
        }
    }
    static void descriptografar(Path origem, Path destino, String senha) throws Exception {
        try (var in = new BufferedInputStream(Files.newInputStream(origem))) {
            byte[] m = in.readNBytes(MAGIA.length); if (!Arrays.equals(m, MAGIA)) throw new IllegalStateException("Arquivo de backup criptografado inválido");
            byte[] sal = in.readNBytes(16), iv = in.readNBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(chave(senha, sal), "AES"), new GCMParameterSpec(128, iv));
            try (var ci = new CipherInputStream(in, c); var out = Files.newOutputStream(destino)) { ci.transferTo(out); }
            catch (IOException e) { Files.deleteIfExists(destino); throw new IllegalStateException("Senha do backup incorreta ou arquivo corrompido"); }
        }
    }

    static String sha256(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte x : d.digest()) s.append(String.format("%02x", x)); return s.toString();
    }

    static void dump(Path pgDump, Banco.Alvo a, String usuario, String senha, String banco, Path saida) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(pgDump.toString(), "-h", a.host(), "-p", a.porta(), "-U", usuario, "--clean", "--if-exists", "--no-owner", "-f", saida.toString(), banco);
        pb.environment().put("PGPASSWORD", senha); pb.redirectErrorStream(true);
        Process p = pb.start(); String t = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("pg_dump de '" + banco + "' falhou: " + t.strip());
    }

    static void zipar(Path zip, Map<String, Path> arquivos) throws IOException {
        try (var z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : arquivos.entrySet()) { if (!Files.isRegularFile(e.getValue())) continue; z.putNextEntry(new ZipEntry(e.getKey())); Files.copy(e.getValue(), z); z.closeEntry(); }
        }
    }

    /** @return 0 ok · 2 backup feito mas alguma cópia falhou */
    static int fazer(Path home, Map<String, String> e, String senhaArg, PrintStream out) throws Exception {
        Banco.Alvo a = Banco.alvo(e.getOrDefault("DB_URL", ""));
        Path pgDump = Banco.ferramenta("pg_dump", e.get("PG_BIN"));
        Path pasta = home.resolve(e.getOrDefault("BACKUP_PASTA", "backups").isBlank() ? "backups" : e.getOrDefault("BACKUP_PASTA", "backups")).normalize(); Files.createDirectories(pasta);
        Path tmp = Files.createTempDirectory("fruteira-backup");
        String carimbo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try {
            Path sqlApp = tmp.resolve("fruteira.sql");
            dump(pgDump, a, e.getOrDefault("DB_USER", "fruteira"), e.getOrDefault("DB_PASS", ""), a.banco(), sqlApp);
            Map<String, Path> itens = new LinkedHashMap<>(); itens.put("fruteira.sql", sqlApp);
            itens.put("config/.env", home.resolve(".env"));
            Path cert = home.resolve("certificado.pfx"); String cp = e.getOrDefault("FISCAL_CERT_PATH", ""); if (!cp.isBlank() && Files.isRegularFile(Paths.get(cp))) cert = Paths.get(cp);
            itens.put("config/certificado.pfx", cert);
            Path zip = tmp.resolve("pacote.zip"); zipar(zip, itens);
            String senha = senhaArg != null ? senhaArg : e.getOrDefault("BACKUP_SENHA", "");
            Path destino = pasta.resolve("fruteira-" + carimbo + "-prod.zip" + (senha.isBlank() ? "" : ".enc"));
            if (senha.isBlank()) { Files.move(zip, destino); out.println("⚠ Backup SEM senha (defina BACKUP_SENHA no .env): ele contém senhas, o certificado digital e dados de clientes."); } else criptografar(zip, destino, senha);
            Files.writeString(Paths.get(destino + ".sha256"), sha256(destino) + "  " + destino.getFileName() + "\n");
            out.println("✔ Backup: " + destino + " (" + Files.size(destino) / 1024 + " KB)");
            int codigo = 0;
            for (String d : e.getOrDefault("BACKUP_DESTINOS", "").split(";")) {
                if (d.isBlank()) continue;
                try { Path dir = Paths.get(d.trim()); Files.createDirectories(dir); Files.copy(destino, dir.resolve(destino.getFileName()), StandardCopyOption.REPLACE_EXISTING); Files.copy(Paths.get(destino + ".sha256"), dir.resolve(destino.getFileName() + ".sha256"), StandardCopyOption.REPLACE_EXISTING); out.println("✔ Cópia em " + d.trim()); }
                catch (Exception ex) { out.println("⚠ Não consegui copiar para " + d.trim() + " (pendrive conectado?): " + ex.getMessage()); codigo = 2; }
            }
            String rclone = e.getOrDefault("RCLONE_DESTINO", "");
            if (!rclone.isBlank()) {
                try { Process p = new ProcessBuilder("rclone", "copy", destino.toString(), rclone).inheritIO().start(); if (p.waitFor() == 0) out.println("✔ Cópia em " + rclone); else { out.println("⚠ rclone falhou"); codigo = 2; } }
                catch (IOException ex) { out.println("⚠ rclone não encontrado: " + ex.getMessage()); codigo = 2; }
            }
            int dias = 30; try { dias = Integer.parseInt(e.getOrDefault("BACKUP_RETENCAO_DIAS", "30").trim()); } catch (NumberFormatException ignorado) { /* padrão */ }
            if (dias > 0) {
                List<Path> pastas = new ArrayList<>(List.of(pasta)); for (String d : e.getOrDefault("BACKUP_DESTINOS", "").split(";")) if (!d.isBlank()) pastas.add(Paths.get(d.trim()));
                for (Path dir : pastas) apagarAntigos(dir, dias, out);
            }
            return codigo;
        } finally { try (var s = Files.walk(tmp)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (IOException ignorado) { /* temp */ } }
    }

    static void apagarAntigos(Path dir, int dias, PrintStream out) {
        if (!Files.isDirectory(dir)) return;
        Instant limite = Instant.now().minus(Duration.ofDays(dias));
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().startsWith("fruteira-") && (x.toString().contains("-prod.zip"))).toList())
                if (Files.getLastModifiedTime(p).toInstant().isBefore(limite)) { Files.deleteIfExists(p); out.println("🗑 Apagado backup antigo: " + p.getFileName()); }
        } catch (IOException ignorado) { /* destino ausente */ }
    }

    /** Recarrega os dois bancos a partir do backup; a configuração (.env, certificado, realm) sai em backups/restaurado-<data>/config sem sobrescrever nada. */
    static void restaurar(Path home, Map<String, String> e, Path arquivo, String senhaArg, boolean confirmado, PrintStream out) throws Exception {
        if (!Files.isRegularFile(arquivo)) throw new IllegalArgumentException("Arquivo não encontrado: " + arquivo);
        Path sum = Paths.get(arquivo + ".sha256");
        if (Files.isRegularFile(sum)) { String esperado = Files.readString(sum).trim().split("\\s+")[0]; if (!esperado.equalsIgnoreCase(sha256(arquivo))) throw new IllegalStateException("O .sha256 não confere: o backup está corrompido"); out.println("✔ Integridade conferida (sha256)"); }
        Path tmp = Files.createTempDirectory("fruteira-restaura");
        try {
            Path zip = arquivo;
            if (arquivo.getFileName().toString().endsWith(".enc")) {
                String senha = senhaArg != null ? senhaArg : e.getOrDefault("BACKUP_SENHA", "");
                if (senha.isBlank()) throw new IllegalArgumentException("Backup criptografado: informe --senha SUASENHA");
                zip = tmp.resolve("pacote.zip"); descriptografar(arquivo, zip, senha);
            }
            try (var z = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry en; (en = z.getNextEntry()) != null; ) { Path d = tmp.resolve("x").resolve(en.getName()).normalize(); if (!d.startsWith(tmp.resolve("x"))) throw new IllegalStateException("Backup com caminho suspeito"); Files.createDirectories(d.getParent()); Files.copy(z, d, StandardCopyOption.REPLACE_EXISTING); }
            }
            if (!confirmado) throw new IllegalStateException("Restaurar APAGA os dados atuais do banco. Pare o sistema (parar) e rode de novo com --sim para confirmar.");
            Banco.Alvo a = Banco.alvo(e.getOrDefault("DB_URL", "")); Path psql = Banco.ferramenta("psql", e.get("PG_BIN"));
            for (String[] b : new String[][] {{"fruteira.sql", e.getOrDefault("DB_USER", "fruteira"), e.getOrDefault("DB_PASS", ""), a.banco()}}) {
                Path sql = tmp.resolve("x").resolve(b[0]); if (!Files.isRegularFile(sql)) { out.println("⚠ " + b[0] + " não está no backup"); continue; }
                var r = Banco.rodar(List.of(psql.toString(), "-X", "-q", "-h", a.host(), "-p", a.porta(), "-U", b[1], "-d", b[3], "-v", "ON_ERROR_STOP=1", "-f", sql.toString()), Map.of("PGPASSWORD", b[2]), null);
                if (r.codigo() != 0) throw new IllegalStateException("Restauração de " + b[3] + " falhou: " + r.texto().strip());
                out.println("✔ Banco '" + b[3] + "' restaurado");
            }
            Path cfg = home.resolve("backups").resolve("restaurado-" + LocalDate.now()).resolve("config"); Files.createDirectories(cfg);
            Path origem = tmp.resolve("x/config");
            if (Files.isDirectory(origem)) try (var s = Files.list(origem)) { for (Path p : s.toList()) Files.copy(p, cfg.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
            out.println("ℹ Configuração do backup em " + cfg + " (não sobrescreve o .env nem o certificado atuais). Reinicie o sistema (iniciar).");
        } finally { try (var s = Files.walk(tmp)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (IOException ignorado) { /* temp */ } }
    }
}
