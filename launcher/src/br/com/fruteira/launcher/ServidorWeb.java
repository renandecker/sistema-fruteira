package br.com.fruteira.launcher;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;
import javax.net.ssl.*;

/**
 * Servidor web da produção (substitui nginx e Node): serve o front compilado (pasta web/), entrega /config.js com o ambiente
 * (assim o front não precisa ser recompilado ao trocar o endereço) e encaminha /api/<serviço>/... aos microsserviços. HTTPS opcional (PKCS12).
 */
final class ServidorWeb {
    private final Path raiz; private final Map<String, Integer> servicos; private final String configJs;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern API = Pattern.compile("^/api/([^/]+)(/.*)?$");
    private static final Set<String> NAO_REPASSAR = Set.of("host", "connection", "content-length", "expect", "upgrade", "transfer-encoding", "keep-alive", "te", "trailer", "proxy-authorization", "proxy-connection", "x-forwarded-for", "x-forwarded-proto");
    private static final Map<String, String> TIPOS = Map.ofEntries(Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("mjs", "text/javascript; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("map", "application/json"));

    ServidorWeb(Path raiz, Map<String, Integer> servicos, String configJs) { this.raiz = raiz.toAbsolutePath().normalize(); this.servicos = servicos; this.configJs = configJs; }

    static String js(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""; }
    private static final String[][] OBS = { {"OBS_OTEL_WEB_ENDPOINT", "otelEndpoint"}, {"OBS_FARO_URL", "faroUrl"}, {"OBS_SENTRY_DSN", "sentryDsn"}, {"OBS_BUGSNAG_KEY", "bugsnagKey"}, {"OBS_BUGSNAG_NOTIFY_URL", "bugsnagNotify"}, {"OBS_BUGSNAG_SESSIONS_URL", "bugsnagSessions"}, {"OBS_ROLLBAR_ENDPOINT", "rollbarEndpoint"},
        {"OBS_ROLLBAR_TOKEN", "rollbarToken"}, {"OBS_POSTHOG_KEY", "posthogKey"}, {"OBS_POSTHOG_HOST", "posthogHost"}, {"OBS_DYNATRACE_SCRIPT", "dynatraceScript"}, {"OBS_REACT_SCAN", "reactScan"},
        {"OBS_PROFILER", "profiler"}, {"OBS_PROFILER_LIMIAR_MS", "profilerLimiarMs"}, {"OBS_AMOSTRAGEM", "amostragem"}, {"OBS_VERSAO", "versao"}, {"OBS_AMBIENTE", "ambiente"} };
    /** Só as chaves de observabilidade preenchidas no .env (segredos de servidor nunca entram: só DSN/chaves públicas de navegador). */
    static String obsJs(Map<String, String> env) {
        StringBuilder b = new StringBuilder("{servico:\"pdv-web\"");
        for (String[] k : OBS) { String v = env.get(k[0]); if (v != null && !v.isBlank()) b.append(",").append(k[1]).append(":").append(js(v.trim())); }
        return b.append("}").toString();
    }
    static String configJs(Map<String, String> env) {
        return "window.__FRUTEIRA__={appEnv:\"producao\",obs:" + obsJs(env) + "};";
    }
    static String configJs() { return "window.__FRUTEIRA__={appEnv:\"producao\"};"; }


    HttpServer iniciar(int porta, String p12, String senha) throws Exception {
        HttpServer srv;
        if (p12 != null && !p12.isBlank()) {
            KeyStore ks = KeyStore.getInstance("PKCS12"); try (var in = new FileInputStream(p12)) { ks.load(in, senha.toCharArray()); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, senha.toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.getKeyManagers(), null, null);
            HttpsServer h = HttpsServer.create(new java.net.InetSocketAddress(porta), 0); h.setHttpsConfigurator(new HttpsConfigurator(ctx)); srv = h;
        } else srv = HttpServer.create(new java.net.InetSocketAddress(porta), 0);
        srv.createContext("/", this::tratar);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.start();
        return srv;
    }

    private void tratar(HttpExchange ex) throws IOException {
        try {
            String caminho = ex.getRequestURI().getRawPath();
            if (caminho.equals("/config.js")) { enviar(ex, 200, "text/javascript; charset=utf-8", configJs.getBytes(StandardCharsets.UTF_8), "no-store"); return; }
            Matcher m = API.matcher(caminho);
            if (m.matches()) { api(ex, m.group(1), m.group(2) == null ? "/" : m.group(2)); return; }
            estatico(ex, java.net.URLDecoder.decode(caminho, StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { enviar(ex, 500, "text/plain; charset=utf-8", "Erro interno".getBytes(StandardCharsets.UTF_8), "no-store"); } catch (IOException ignorado) { /* cliente saiu */ }
        } finally { ex.close(); }
    }

    private void api(HttpExchange ex, String servico, String resto) throws IOException {
        Integer porta = servicos.get(servico);
        if (porta == null || resto.startsWith("/q/")) { enviar(ex, 404, "text/plain; charset=utf-8", new byte[0], "no-store"); return; }       // nunca expõe /q/ (swagger, health…)
        String q = ex.getRequestURI().getRawQuery();
        byte[] corpo = ex.getRequestBody().readAllBytes();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + resto + (q == null ? "" : "?" + q))).timeout(Duration.ofSeconds(120))
            .method(ex.getRequestMethod(), corpo.length == 0 && !Set.of("POST", "PUT", "PATCH").contains(ex.getRequestMethod()) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corpo));
        for (var h : ex.getRequestHeaders().entrySet()) if (!NAO_REPASSAR.contains(h.getKey().toLowerCase())) for (String v : h.getValue()) rb.header(h.getKey(), v);
        rb.header("X-Forwarded-For", ex.getRemoteAddress().getAddress().getHostAddress()); rb.header("X-Forwarded-Proto", ex.getHttpContext().getServer() instanceof HttpsServer ? "https" : "http");
        try {
            HttpResponse<byte[]> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            for (var h : r.headers().map().entrySet()) { String k = h.getKey().toLowerCase(); if (!Set.of("transfer-encoding", "connection", "content-length", "keep-alive").contains(k)) for (String v : h.getValue()) ex.getResponseHeaders().add(h.getKey(), v); }
            byte[] b = r.body(); boolean semCorpo = b.length == 0 || ex.getRequestMethod().equals("HEAD") || r.statusCode() == 204 || r.statusCode() == 304;
            ex.sendResponseHeaders(r.statusCode(), semCorpo ? -1 : b.length);
            if (!semCorpo) ex.getResponseBody().write(b);
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) { enviar(ex, 502, "text/plain; charset=utf-8", "Serviço indisponível".getBytes(StandardCharsets.UTF_8), "no-store"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); enviar(ex, 502, "text/plain; charset=utf-8", "Interrompido".getBytes(StandardCharsets.UTF_8), "no-store"); }
    }

    private void estatico(HttpExchange ex, String caminho) throws IOException {
        Path alvo = raiz.resolve("." + caminho).normalize();
        if (!alvo.startsWith(raiz)) { enviar(ex, 403, "text/plain; charset=utf-8", new byte[0], "no-store"); return; }
        Path f = Files.isRegularFile(alvo) ? alvo : raiz.resolve("index.html");                                         // SPA: rota desconhecida → index.html
        if (!Files.isRegularFile(f)) { enviar(ex, 404, "text/plain; charset=utf-8", "Front não encontrado (pasta web/)".getBytes(StandardCharsets.UTF_8), "no-store"); return; }
        String nome = f.getFileName().toString(); String ext = nome.contains(".") ? nome.substring(nome.lastIndexOf('.') + 1).toLowerCase() : "";
        boolean imutavel = raiz.relativize(f).startsWith("assets");
        enviar(ex, 200, TIPOS.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(f), imutavel ? "public, max-age=31536000, immutable" : "no-cache");
    }

    private static void enviar(HttpExchange ex, int status, String tipo, byte[] corpo, String cache) throws IOException {
        Headers h = ex.getResponseHeaders(); h.set("Content-Type", tipo); h.set("Cache-Control", cache); h.set("X-Frame-Options", "SAMEORIGIN"); h.set("X-Content-Type-Options", "nosniff");
        boolean semCorpo = corpo.length == 0 || ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, semCorpo ? -1 : corpo.length);
        if (!semCorpo) ex.getResponseBody().write(corpo);
    }
}
