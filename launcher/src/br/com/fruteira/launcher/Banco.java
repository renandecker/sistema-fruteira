package br.com.fruteira.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Prepara o PostgreSQL já instalado na máquina (banco e usuário da Fruteira) usando o psql que vem com ele. */
final class Banco {
    private Banco() {}
    record Alvo(String host, String porta, String banco) {}
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,40}");

    static Alvo alvo(String jdbcUrl) {
        Matcher m = Pattern.compile("^jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+)").matcher(jdbcUrl == null ? "" : jdbcUrl.trim());
        if (!m.find()) throw new IllegalArgumentException("DB_URL inválida (esperado jdbc:postgresql://servidor:5432/fruteira): " + jdbcUrl);
        return new Alvo(m.group(1), m.group(2) == null ? "5432" : m.group(2), m.group(3));
    }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** Acha psql/pg_dump: PG_BIN, PATH, pastas padrão do instalador (Windows e Linux). */
    static Path ferramenta(String nome, String pgBin) {
        String exe = windows() ? nome + ".exe" : nome;
        List<Path> dirs = new ArrayList<>();
        if (pgBin != null && !pgBin.isBlank()) dirs.add(Paths.get(pgBin));
        String path = System.getenv("PATH"); if (path != null) for (String d : path.split(File.pathSeparator)) if (!d.isBlank()) dirs.add(Paths.get(d));
        for (String base : new String[] {"C:\\Program Files\\PostgreSQL", "/usr/lib/postgresql", "/Library/PostgreSQL", "/opt/homebrew/opt"}) {
            Path b = Paths.get(base); if (!Files.isDirectory(b)) continue;
            try (var s = Files.list(b)) { s.sorted(Comparator.reverseOrder()).forEach(v -> { dirs.add(v.resolve("bin")); dirs.add(v.resolve("bin")); }); } catch (IOException ignorado) { /* segue */ }
        }
        for (Path d : dirs) { Path f = d.resolve(exe); if (Files.isRegularFile(f)) return f; }
        throw new IllegalStateException("'" + nome + "' não encontrado: instale o PostgreSQL (ele traz o " + nome + ") ou informe a pasta bin dele em PG_BIN no .env");
    }

    record Saida(int codigo, String texto) {}
    static Saida rodar(List<String> cmd, Map<String, String> env, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true); pb.environment().putAll(env);
        Process p = pb.start();
        try (var o = p.getOutputStream()) { if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8)); }
        String t = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Saida(p.waitFor(), t);
    }

    static String ident(String v, String quem) {
        if (!IDENT.matcher(v).matches()) throw new IllegalArgumentException(quem + " inválido ('" + v + "'): use só letras, números e _");
        return v;
    }

    static final String SQL_PAPEL_E_BANCO = """
        SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'u', :'pw') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'u') \\gexec
        SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'u', :'pw') \\gexec
        SELECT format('CREATE DATABASE %I OWNER %I ENCODING ''UTF8'' TEMPLATE template0', :'d', :'u') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'d') \\gexec
        """;
    static final String SQL_SO_BANCO = """
        SELECT format('CREATE DATABASE %I ENCODING ''UTF8'' TEMPLATE template0', :'d') WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'d') \\gexec
        """;

    /** Cria (se faltar) o banco da Fruteira e acerta as senhas conforme o .env. Idempotente. */
    static void preparar(Map<String, String> e, PrintStream out) throws Exception {
        Alvo a = alvo(e.getOrDefault("DB_URL", ""));
        Path psql = ferramenta("psql", e.get("PG_BIN"));
        String admin = e.getOrDefault("PG_ADMIN_USER", "postgres");
        String adminPass = e.getOrDefault("PG_ADMIN_PASS", ""); if (adminPass.isBlank()) adminPass = e.getOrDefault("DB_PASS", "");
        String dbUser = ident(e.getOrDefault("DB_USER", "fruteira"), "DB_USER"), banco = ident(a.banco(), "nome do banco em DB_URL");
        Map<String, String> pg = new HashMap<>(Map.of("PGPASSWORD", adminPass, "PGCONNECT_TIMEOUT", "10", "PGCLIENTENCODING", "UTF8"));
        List<String> base = List.of(psql.toString(), "-X", "-q", "-h", a.host(), "-p", a.porta(), "-U", admin, "-d", "postgres", "-v", "ON_ERROR_STOP=1");
        Saida teste = rodar(concat(base, "-c", "SELECT 1"), pg, null);
        if (teste.codigo() != 0) {
            String t = teste.texto();
            if (t.contains("password authentication failed") || t.contains("autenticação do tipo senha falhou"))
                throw new IllegalStateException("O PostgreSQL recusou a senha do usuário '" + admin + "'. Informe em PG_ADMIN_PASS (no .env) a senha do usuário administrador que você definiu ao instalar o PostgreSQL.");
            if (t.contains("could not connect") || t.contains("Connection refused") || t.contains("não foi possível conectar") || t.contains("timeout"))
                throw new IllegalStateException("Não consegui falar com o PostgreSQL em " + a.host() + ":" + a.porta() + ". Ele está instalado e em execução (serviço iniciado)? Detalhe: " + t.strip());
            throw new IllegalStateException("Falha ao conectar no PostgreSQL: " + t.strip());
        }
        // banco da aplicação
        if (dbUser.equals(admin)) {
            exec(concat(base, "-v", "d=" + banco, "-f", "-"), pg, SQL_SO_BANCO);
        } else {
            exec(concat(base, "-v", "u=" + dbUser, "-v", "pw=" + e.getOrDefault("DB_PASS", ""), "-v", "d=" + banco, "-f", "-"), pg, SQL_PAPEL_E_BANCO);
        }
        out.println("✔ PostgreSQL pronto em " + a.host() + ":" + a.porta() + " (banco '" + banco + "')");
    }

    private static void exec(List<String> cmd, Map<String, String> env, String sql) throws Exception {
        Saida s = rodar(cmd, env, sql);
        if (s.codigo() != 0) throw new IllegalStateException("O PostgreSQL recusou a preparação do banco: " + s.texto().strip());
    }
    static List<String> concat(List<String> a, String... b) { List<String> l = new ArrayList<>(a); l.addAll(Arrays.asList(b)); return l; }
}
