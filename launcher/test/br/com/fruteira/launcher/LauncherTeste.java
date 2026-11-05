package br.com.fruteira.launcher;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Testes do launcher (só JDK). Com PostgreSQL de teste: -Dpg.port=5433 -Dpg.pass=SENHA_DO_POSTGRES (senão pula banco/backup). ./testar.sh */
public class LauncherTeste {
    static int ok = 0, falhas = 0;
    static void confere(boolean c, String nome) { if (c) { ok++; System.out.println("  ✔ " + nome); } else { falhas++; System.out.println("  ✘ FALHOU: " + nome); } }
    static final HttpClient http = HttpClient.newHttpClient();
    static HttpResponse<String> get(String url, String... h) throws Exception { var b = HttpRequest.newBuilder(URI.create(url)); for (int i = 0; i + 1 < h.length; i += 2) b.header(h[i], h[i + 1]); return http.send(b.build(), HttpResponse.BodyHandlers.ofString()); }

    public static void main(String[] a) throws Exception {
        try { executar(); } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void executar() throws Exception {
        Path tmp = Files.createTempDirectory("launcher");
        System.out.println(".env"); env(tmp);
        System.out.println("Certificado HTTPS"); cert(tmp);
        System.out.println("Agente TEF do caixa"); tefCaixa();
        System.out.println("OpenTelemetry/Dynatrace"); otel();
        System.out.println("Servidor web"); web(tmp);
        String pgPort = System.getProperty("pg.port");
        if (pgPort != null) { System.out.println("PostgreSQL"); banco(tmp, pgPort, System.getProperty("pg.pass")); } else System.out.println("PostgreSQL: pulado (sem -Dpg.port)");
        System.out.println("Backup criptografado"); cripto(tmp);
        System.out.println("\n" + ok + " ok, " + falhas + " falha(s)"); System.exit(falhas > 0 ? 1 : 0);
    }

    static java.util.Map<String, String> mapa(String... kv) { var m = new java.util.LinkedHashMap<String, String>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
    static void cert(Path tmp) throws Exception {
        Path h = Files.createTempDirectory(tmp, "cert"); Files.writeString(h.resolve(".env"), "WEB_PORT=5173\nFRONT_URL=http://x\n"); Main.home = h; Main.out = System.out;
        Main.certificado(new String[] {"certificado", "192.168.0.10", "servidor-loja"});
        var e = new Env(h.resolve(".env"));
        confere(Files.exists(h.resolve("certs/fruteira.p12")) && Files.exists(h.resolve("certs/fruteira.cer")), "certificado: .p12 e .cer criados");
        confere("https://192.168.0.10:5173".equals(e.get("FRONT_URL")) && !e.get("WEB_TLS_SENHA").isBlank() && e.get("WEB_TLS_P12").endsWith("fruteira.p12"), "certificado: .env atualizado (FRONT_URL https, senha e arquivo do TLS)");
        var ks = java.security.KeyStore.getInstance("PKCS12"); try (var in = Files.newInputStream(h.resolve("certs/fruteira.p12"))) { ks.load(in, e.get("WEB_TLS_SENHA").toCharArray()); }
        var c = (java.security.cert.X509Certificate) ks.getCertificate("fruteira");
        String sans = String.valueOf(c.getSubjectAlternativeNames());
        confere(sans.contains("192.168.0.10") && sans.contains("servidor-loja") && sans.contains("127.0.0.1"), "certificado: SAN com IP, nome e localhost");
        boolean recusou = false; try { Main.certificado(new String[] {"certificado", "192.168.0.10"}); } catch (IllegalStateException ex) { recusou = true; }
        confere(recusou, "certificado: não sobrescreve sem --forcar");
        boolean invalido = false; try { Main.certificado(new String[] {"certificado", "a b;rm"}); } catch (IllegalArgumentException ex) { invalido = true; }
        confere(invalido, "certificado: nome inválido recusado");
    }
    static void tefCaixa() {
        var mm = Main.ambienteTef(mapa("TEF_PROVIDER", "stone", "STONE_CODIGO", "123", "STONE_TERMINAL_SERIAL", "S1", "CIELO_EC", "", "BB_EC", "9"), Path.of("/x"));
        confere("stone".equals(mm.get("TEF_PROVIDER")) && "123".equals(mm.get("STONE_CODIGO")) && "S1".equals(mm.get("STONE_TERMINAL_SERIAL")) && "9".equals(mm.get("BB_EC")) && !mm.containsKey("CIELO_EC"), "agente TEF recebe STONE_*/CIELO_*/BB_* do .env (só os preenchidos)");
        var m = Main.ambienteTef(mapa("FRONT_URL", "https://192.168.0.10:5173", "TEF_TOKEN", "tok", "TEF_PROVIDER", "clisitef"), Path.of("/x"));
        confere("https://192.168.0.10:5173".equals(m.get("TEF_ORIGENS")) && "tok".equals(m.get("TEF_TOKEN")) && "clisitef".equals(m.get("TEF_PROVIDER")), "agente do caixa: origem = FRONT_URL, token e provedor do .env");
        confere("127.0.0.1".equals(m.get("QUARKUS_HTTP_HOST")) && "8090".equals(m.get("QUARKUS_HTTP_PORT")), "agente do caixa: escuta só em 127.0.0.1:8090");
    }
    static void otel() {
        var m = mapa("OTEL_ENABLED", "false", "DYNATRACE_TOKEN", "dt0s16.X"); Main.mapearOtel(m);
        confere("true".equals(m.get("QUARKUS_OTEL_SDK_DISABLED")) && !m.containsKey("QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT"), "OTEL_ENABLED=false mantém o SDK desligado");
        m = mapa("OTEL_ENABLED", "true", "DYNATRACE_OTLP_URL", "https://abc12345.live.dynatrace.com/api/v2/otlp/v1/traces/", "DYNATRACE_TOKEN", "dt0s16.ABC.DEF"); Main.mapearOtel(m);
        confere("https://abc12345.live.dynatrace.com/api/v2/otlp".equals(m.get("QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT")), "Dynatrace: URL normalizada (sem /v1/traces nem barra final)");
        confere("Authorization=Bearer dt0s16.ABC.DEF".equals(m.get("QUARKUS_OTEL_EXPORTER_OTLP_HEADERS")), "Dynatrace: cabeçalho Bearer por padrão");
        confere("http/protobuf".equals(m.get("QUARKUS_OTEL_EXPORTER_OTLP_PROTOCOL")) && "http/protobuf".equals(m.get("QUARKUS_OTEL_EXPORTER_OTLP_TRACES_PROTOCOL")) && "false".equals(m.get("QUARKUS_OTEL_SDK_DISABLED")), "Dynatrace: http/protobuf (sem gRPC) e SDK ligado");
        m = mapa("OTEL_ENABLED", "true", "DYNATRACE_OTLP_URL", "https://x.live.dynatrace.com/api/v2/otlp", "DYNATRACE_TOKEN", "dt0c01.A", "DYNATRACE_AUTH", "Api-Token"); Main.mapearOtel(m);
        confere("Authorization=Api-Token dt0c01.A".equals(m.get("QUARKUS_OTEL_EXPORTER_OTLP_HEADERS")), "Dynatrace: token clássico com Api-Token");
        for (var ruim : java.util.List.of(mapa("DYNATRACE_OTLP_URL", "http://x/api/v2/otlp", "DYNATRACE_TOKEN", "t"), mapa("DYNATRACE_OTLP_URL", "https://x/api/v2/otlp"), mapa("DYNATRACE_OTLP_URL", "https://x/otlp", "DYNATRACE_TOKEN", "a b"), mapa("DYNATRACE_OTLP_URL", "https://x/otlp", "DYNATRACE_TOKEN", "t", "DYNATRACE_AUTH", "Basic"))) {
            ruim.put("OTEL_ENABLED", "true"); boolean erro = false; try { Main.mapearOtel(ruim); } catch (IllegalStateException e) { erro = true; }
            confere(erro, "Dynatrace: configuração inválida é recusada (" + ruim.keySet() + ")");
        }
        var c = ServidorWeb.configJs(mapa("OBS_ROLLBAR_TOKEN", "rt", "OBS_BUGSNAG_KEY", "bk", "OBS_ROLLBAR_ENDPOINT", "https://api.eu.rollbar.com/api/1/item/", "DYNATRACE_TOKEN", "segredo"));
        confere(c.contains("rollbarToken:\"rt\"") && c.contains("bugsnagKey:\"bk\"") && c.contains("rollbarEndpoint:") && !c.contains("segredo"), "/config.js leva Rollbar e Bugsnag e nunca o token do Dynatrace");
    }
    static void env(Path tmp) throws Exception {
        Path f = tmp.resolve(".env");
        Files.writeString(f, "# comentario\r\nAPP_ENV=desenvolvimento\r\nDB_PASS=troque-a-senha\r\n#TEF_TOKEN=\r\nFISCAL_CNPJ=\"12345678000195\"\r\nVAZIA=\r\n");
        Env e = new Env(f);
        confere(e.get("APP_ENV").equals("desenvolvimento") && e.get("FISCAL_CNPJ").equals("12345678000195") && e.get("NAO_TEM").isEmpty(), "lê valores (CRLF, aspas) e ausentes");
        e.set("APP_ENV", "producao"); confere(e.get("APP_ENV").equals("producao"), "troca a linha ativa");
        var g = e.gerarSenhas("DB_PASS", "TEF_TOKEN", "JWT_SEGREDO");
        confere(g.equals(List.of("DB_PASS", "TEF_TOKEN", "JWT_SEGREDO")) && e.get("JWT_SEGREDO").length() == 48, "gera senha para vazio, 'troque...' e ausente (JWT_SEGREDO com 48 caracteres)");
        confere(e.get("DB_PASS").length() == 24 && e.get("TEF_TOKEN").length() == 40 && e.get("DB_PASS").matches("[A-Za-z0-9]+"), "senhas aleatórias (24 / 40 caracteres)");
        Env r = new Env(f); String ds = r.get("DB_PASS");
        confere(ds.equals(e.get("DB_PASS")) && r.gerarSenhas("DB_PASS", "TEF_TOKEN").isEmpty(), "gravou no arquivo e não troca senha já definida");
        confere(Files.readString(f).contains("# comentario") && !Files.readString(f).contains("#TEF_TOKEN="), "preserva comentários e descomenta a chave");
        confere(Env.senha(24).equals(Env.senha(24)) == false, "senhas diferentes a cada chamada");
    }

    static void web(Path tmp) throws Exception {
        Path web = Files.createDirectories(tmp.resolve("web/assets")).getParent();
        Files.writeString(web.resolve("index.html"), "<html>INDEX</html>"); Files.writeString(web.resolve("assets/app.js"), "console.log(1)"); Files.writeString(tmp.resolve("segredo.txt"), "SEGREDO");
        HttpServer back = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        back.createContext("/", ex -> {
            byte[] corpo = ex.getRequestBody().readAllBytes();
            String r = ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " body=" + new String(corpo, StandardCharsets.UTF_8) + " xff=" + ex.getRequestHeaders().getFirst("X-Forwarded-For");
            byte[] b = ex.getRequestURI().getPath().equals("/vazio") ? new byte[0] : r.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("X-Teste", "sim"); ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/vazio") ? 204 : 201, b.length == 0 ? -1 : b.length); if (b.length > 0) ex.getResponseBody().write(b); ex.close();
        });
        back.start();
        int pb = back.getAddress().getPort();
        var srv = new ServidorWeb(web, Map.of("catalogo", pb), ServidorWeb.configJs()).iniciar(0, null, null);
        String u = "http://127.0.0.1:" + srv.getAddress().getPort();
        var c = get(u + "/config.js");
        confere(c.statusCode() == 200 && c.body().contains("appEnv:\"producao\"") && !c.body().contains("kc:") && c.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"), "/config.js entrega o ambiente de produção");
        confere(ServidorWeb.configJs(Map.of("OBS_SENTRY_DSN", "a\"b</script>")).contains("a\\\"b\\u003c/script>"), "config.js escapa aspas e </script>");
        var jw = Main.jwkDe("segredo-de-teste-com-mais-de-32-caracteres!");
        confere(jw.contains("\"kty\":\"oct\"") && jw.contains("\"k\":\"" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("segredo-de-teste-com-mais-de-32-caracteres!".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\""), "JWK oct (HS256) com o segredo em base64url");
        confere(get(u + "/").body().equals("<html>INDEX</html>"), "/ serve index.html");
        var js = get(u + "/assets/app.js");
        confere(js.body().equals("console.log(1)") && js.headers().firstValue("Cache-Control").orElse("").contains("immutable") && js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"), "assets: tipo e cache imutável");
        confere(get(u + "/rota/qualquer").body().equals("<html>INDEX</html>") && get(u + "/").headers().firstValue("Cache-Control").orElse("").equals("no-cache"), "rota do SPA cai no index.html (sem cache)");
        confere(get(u + "/..%2Fsegredo.txt").statusCode() == 403 && !get(u + "/../segredo.txt").body().contains("SEGREDO") && !get(u + "/%2e%2e/segredo.txt").body().contains("SEGREDO"), "não sai da pasta web (path traversal → 403)");
        var p = get(u + "/api/catalogo/produtos?x=1", "Authorization", "Bearer abc");
        confere(p.statusCode() == 201 && p.body().startsWith("GET /produtos?x=1 auth=Bearer abc") && p.body().contains("xff=127.0.0.1") && "sim".equals(p.headers().firstValue("X-Teste").orElse("")), "proxy GET: remove /api/catalogo, repassa Authorization/query/cabeçalhos");
        var po = http.send(HttpRequest.newBuilder(URI.create(u + "/api/catalogo/vendas")).POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        confere(po.body().contains("POST /vendas") && po.body().contains("body={\"a\":1}"), "proxy POST com corpo");
        confere(get(u + "/api/catalogo/vazio").statusCode() == 204, "proxy: resposta 204 sem corpo");
        confere(get(u + "/api/catalogo/q/health").statusCode() == 404 && get(u + "/api/naoexiste/x").statusCode() == 404, "bloqueia /q/ e serviço desconhecido");
        back.stop(0);
        confere(get(u + "/api/catalogo/produtos").statusCode() == 502, "serviço fora do ar → 502");
        srv.stop(0);
        // HTTPS opcional
        Path p12 = tmp.resolve("web.p12");
        new ProcessBuilder("keytool", "-genkeypair", "-alias", "w", "-keyalg", "RSA", "-keysize", "2048", "-validity", "5", "-storetype", "PKCS12", "-keystore", p12.toString(), "-storepass", "s3nha123", "-dname", "CN=localhost", "-ext", "san=ip:127.0.0.1").redirectErrorStream(true).start().waitFor();
        var tls = new ServidorWeb(web, Map.of(), "x").iniciar(0, p12.toString(), "s3nha123");
        var ctx = javax.net.ssl.SSLContext.getInstance("TLS"); ctx.init(null, new javax.net.ssl.TrustManager[] {new javax.net.ssl.X509TrustManager() { public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) {} public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) {} public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; } }}, null);
        var h = HttpClient.newBuilder().sslContext(ctx).build().send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + tls.getAddress().getPort() + "/")).build(), HttpResponse.BodyHandlers.ofString());
        confere(h.body().equals("<html>INDEX</html>"), "HTTPS com keystore PKCS12 (WEB_TLS_P12)");
        tls.stop(0);
    }

    static void banco(Path tmp, String port, String admin) throws Exception {
        Map<String, String> e = new HashMap<>(Map.of("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/fruteira_t", "DB_USER", "fruteira_t", "DB_PASS", "Senha'com\"aspas$1", "PG_ADMIN_PASS", admin));
        Banco.preparar(e, System.out);
        var q = Banco.rodar(List.of(Banco.ferramenta("psql", null).toString(), "-X", "-At", "-h", "127.0.0.1", "-p", port, "-U", "fruteira_t", "-d", "fruteira_t", "-c", "select current_user, current_database()"), Map.of("PGPASSWORD", "Senha'com\"aspas$1"), null);
        confere(q.codigo() == 0 && q.texto().strip().equals("fruteira_t|fruteira_t"), "cria usuário e banco da aplicação (senha com aspas e $) e conecta com ele");
        e.put("DB_PASS", "novaSenha1"); Banco.preparar(e, System.out);
        var q2 = Banco.rodar(List.of(Banco.ferramenta("psql", null).toString(), "-X", "-At", "-h", "127.0.0.1", "-p", port, "-U", "fruteira_t", "-d", "fruteira_t", "-c", "select 1"), Map.of("PGPASSWORD", "novaSenha1"), null);
        confere(q2.codigo() == 0, "idempotente: roda de novo e acerta a senha nova do .env");
        e.put("PG_ADMIN_PASS", "errada");
        try { Banco.preparar(e, System.out); confere(false, "senha do admin errada"); } catch (IllegalStateException ex) { confere(ex.getMessage().contains("PG_ADMIN_PASS"), "senha do admin errada → mensagem manda usar PG_ADMIN_PASS"); }
        e.put("PG_ADMIN_PASS", admin); e.put("DB_URL", "jdbc:postgresql://127.0.0.1:59999/fruteira_t");
        try { Banco.preparar(e, System.out); confere(false, "PostgreSQL parado"); } catch (IllegalStateException ex) { confere(ex.getMessage().contains("Não consegui falar com o PostgreSQL"), "PostgreSQL fora do ar → mensagem clara"); }
        e.put("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/x"); e.put("DB_USER", "x;drop");
        try { Banco.preparar(e, System.out); confere(false, "identificador"); } catch (IllegalArgumentException ex) { confere(true, "DB_USER com caracteres perigosos é recusado"); }

        // backup e restauração de ponta a ponta
        e.put("DB_USER", "fruteira_t"); e.put("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/fruteira_t");
        Path home = Files.createDirectories(tmp.resolve("home")); Files.writeString(home.resolve(".env"), "DB_PASS=novaSenha1\n");
        Files.writeString(home.resolve("certificado.pfx"), "CERT");
        String psql = Banco.ferramenta("psql", null).toString();
        Banco.rodar(List.of(psql, "-X", "-h", "127.0.0.1", "-p", port, "-U", "fruteira_t", "-d", "fruteira_t", "-c", "create table venda(id int, total numeric); insert into venda values (1, 10.50), (2, 99)"), Map.of("PGPASSWORD", "novaSenha1"), null);
        e.put("BACKUP_SENHA", "segredo-do-backup"); e.put("BACKUP_DESTINOS", tmp.resolve("pendrive").toString());
        int cod = Backup.fazer(home, e, null, System.out);
        List<Path> arqs; try (var s = Files.list(home.resolve("backups"))) { arqs = s.filter(p -> p.toString().endsWith(".zip.enc")).toList(); }
        confere(cod == 0 && arqs.size() == 1 && Files.exists(Paths.get(arqs.get(0) + ".sha256")), "backup criptografado + .sha256");
        confere(Files.exists(tmp.resolve("pendrive").resolve(arqs.get(0).getFileName())), "cópia extra no pendrive/Drive (BACKUP_DESTINOS)");
        Banco.rodar(List.of(psql, "-X", "-h", "127.0.0.1", "-p", port, "-U", "fruteira_t", "-d", "fruteira_t", "-c", "delete from venda; insert into venda values (3, 1)"), Map.of("PGPASSWORD", "novaSenha1"), null);
        try { Backup.restaurar(home, e, arqs.get(0), "senha-errada", true, System.out); confere(false, "senha errada"); } catch (IllegalStateException ex) { confere(ex.getMessage().contains("Senha do backup incorreta"), "restaurar com senha errada é recusado"); }
        try { Backup.restaurar(home, e, arqs.get(0), null, false, System.out); confere(false, "sem --sim"); } catch (IllegalStateException ex) { confere(ex.getMessage().contains("--sim"), "restaurar exige confirmação (--sim)"); }
        Backup.restaurar(home, e, arqs.get(0), null, true, System.out);
        var v = Banco.rodar(List.of(psql, "-X", "-At", "-h", "127.0.0.1", "-p", port, "-U", "fruteira_t", "-d", "fruteira_t", "-c", "select count(*), sum(total) from venda"), Map.of("PGPASSWORD", "novaSenha1"), null);
        confere(v.texto().strip().equals("2|109.50"), "restaurar devolve os dados do momento do backup: " + v.texto().strip());
        confere(Files.exists(home.resolve("backups/restaurado-" + java.time.LocalDate.now() + "/config/certificado.pfx")), "configuração e certificado saem em backups/restaurado-<data>/config");
        Files.write(arqs.get(0), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        try { Backup.restaurar(home, e, arqs.get(0), null, true, System.out); confere(false, "corrompido"); } catch (IllegalStateException ex) { confere(ex.getMessage().contains("sha256"), "arquivo adulterado: .sha256 não confere"); }
    }

    static void cripto(Path tmp) throws Exception {
        Path o = tmp.resolve("o.bin"), c = tmp.resolve("c.enc"), d = tmp.resolve("d.bin"); byte[] dados = new byte[300_000]; new Random(7).nextBytes(dados); Files.write(o, dados);
        Backup.criptografar(o, c, "abc"); Backup.descriptografar(c, d, "abc");
        confere(Arrays.equals(dados, Files.readAllBytes(d)) && Files.size(c) > dados.length && !Arrays.equals(Arrays.copyOfRange(Files.readAllBytes(c), 40, 80), Arrays.copyOfRange(dados, 0, 40)), "AES-256-GCM: ida e volta (300 KB)");
        try { Backup.descriptografar(c, tmp.resolve("e.bin"), "errada"); confere(false, "senha errada"); } catch (IllegalStateException ex) { confere(!Files.exists(tmp.resolve("e.bin")), "senha errada: recusa e não deixa arquivo parcial"); }
        byte[] adulterado = Files.readAllBytes(c); adulterado[200] ^= 1; Files.write(c, adulterado);
        try { Backup.descriptografar(c, tmp.resolve("f.bin"), "abc"); confere(false, "adulterado"); } catch (IllegalStateException ex) { confere(true, "arquivo adulterado é detectado (GCM)"); }
    }
}
