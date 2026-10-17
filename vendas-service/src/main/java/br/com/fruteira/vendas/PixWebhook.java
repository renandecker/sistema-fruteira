package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Descobre a URL PÚBLICA do webhook do Pix, a mesma para todos os provedores (registrada no PSP pelo botão do gerente,
 * ou enviada em cada cobrança no Mercado Pago). Ordem:
 *   1) FRUTEIRA_PIX_WEBHOOK_URL (URL fixa, ex.: produção com domínio próprio);
 *   2) NGROK_URL (túnel com endereço conhecido: https://xxxx.ngrok-free.app ou o domínio reservado);
 *   3) o próprio Ngrok: pergunta ao agente local (http://127.0.0.1:4040/api/tunnels) qual é o endereço do túnel de agora
 *      (no plano grátis ele muda a cada vez que o Ngrok é iniciado). Só em desenvolvimento, a menos que NGROK_API seja definida.
 * Nos casos 2 e 3 a URL é montada assim: {túnel}{NGROK_CAMINHO}?token={segredo} (o caminho padrão, /pix/webhook, é o do vendas-service:
 * o túnel deve apontar direto para a porta dele, `ngrok http 8083`).
 * Só JDK + Jackson (testado por testar-pix.sh).
 */
public final class PixWebhook {
    private PixWebhook() {}

    public static final String CAMINHO_PADRAO = "/pix/webhook";
    /** Domínios que o Ngrok usa nos endereços públicos dos túneis. */
    private static final String[] DOMINIOS_NGROK = { ".ngrok-free.app", ".ngrok-free.dev", ".ngrok.app", ".ngrok.dev", ".ngrok.io", ".ngrok.pizza" };
    private static final ObjectMapper M = new ObjectMapper();
    private static final long VALIDADE_MS = 30_000;
    private record Detectado(String url, long ate) {}
    private static volatile Detectado cache;

    /** A URL resolvida e de onde ela veio. */
    public record Resolvida(String url, String origem) {
        /** A URL sem o segredo (token=...), para mostrar na tela e no log. */
        public String mascarada() { return mascarar(url); }
    }

    /** Resolve a URL do webhook (vazio = nenhuma configurada nem túnel ativo). */
    public static Optional<Resolvida> resolver(PixConfig c) {
        var w = c.webhook();
        Optional<String> fixa = w.url().map(String::trim).filter(s -> !s.isBlank());
        if (fixa.isPresent()) return Optional.of(new Resolvida(fixa.get(), "FRUTEIRA_PIX_WEBHOOK_URL"));
        String segredo = w.segredo().map(String::trim).orElse("");
        String caminho = w.ngrokCaminho().map(String::trim).filter(s -> !s.isBlank()).orElse(CAMINHO_PADRAO);
        Optional<String> manual = w.ngrokUrl().map(String::trim).filter(s -> !s.isBlank());
        if (manual.isPresent()) return Optional.of(new Resolvida(montar(manual.get(), caminho, segredo), "NGROK_URL"));
        Optional<String> api = w.ngrokApi().map(String::trim).filter(s -> !s.isBlank() && !s.equals("-"));
        if (api.isPresent()) {
            int porta = w.ngrokPorta().filter(p -> p > 0).orElse(8083);
            Optional<String> t = tunel(api.get(), porta);
            if (t.isPresent()) return Optional.of(new Resolvida(montar(t.get(), caminho, segredo), "Ngrok (detectado em " + hostDe(api.get()) + ")"));
        }
        return Optional.empty();
    }

    public static Optional<String> url(PixConfig c) { return resolver(c).map(Resolvida::url); }

    /** {base}{caminho}?token={segredo}: base sem barra final, caminho com uma barra inicial, token codificado (se houver segredo). */
    static String montar(String base, String caminho, String segredo) {
        String b = base.trim().replaceAll("/+$", "");
        if (!b.matches("(?i)^https?://.*")) b = "https://" + b;
        String cam = caminho.startsWith("/") ? caminho : "/" + caminho;
        if (segredo == null || segredo.isBlank() || cam.contains("token=")) return b + cam;
        return b + cam + (cam.contains("?") ? "&" : "?") + "token=" + URLEncoder.encode(segredo, StandardCharsets.UTF_8);
    }

    static String mascarar(String url) { return url == null ? "" : url.replaceAll("(?i)([?&]token=)[^&]*", "$1***"); }

    /** Endereço https do túnel que aponta para a porta do vendas-service, perguntando ao agente local do Ngrok (cache de 30 s). */
    static Optional<String> tunel(String api, int porta) {
        Detectado d = cache;
        long agora = System.currentTimeMillis();
        if (d != null && d.ate() > agora) return Optional.ofNullable(d.url());
        String achado = null;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 == 2) achado = escolherTunel(M.readTree(r.body()), porta);
        } catch (Exception e) { /* Ngrok não está rodando: sem túnel */ }
        cache = new Detectado(achado, agora + VALIDADE_MS);
        return Optional.ofNullable(achado);
    }

    /** Dos túneis de {"tunnels":[{"public_url","proto","config":{"addr"}}]}: o https cujo destino é a porta; senão, o único https. */
    static String escolherTunel(JsonNode raiz, int porta) {
        String unico = null; int https = 0;
        for (JsonNode t : raiz.path("tunnels")) {
            String url = t.path("public_url").asText("");
            if (!url.toLowerCase().startsWith("https://")) continue;
            https++; unico = url;
            String addr = t.path("config").path("addr").asText("");
            if (addr.endsWith(":" + porta) || addr.equals(String.valueOf(porta))) return url;
        }
        return https == 1 ? unico : null;
    }

    /** Esquece o endereço guardado (usado ao registrar o webhook, para pegar o túnel de agora). */
    public static void esquecer() { cache = null; }

    /** O host é de um túnel do Ngrok (domínios do Ngrok, ou o host de NGROK_URL / do túnel detectado)? */
    public static boolean ehHostDoTunel(String host, PixConfig c) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase().replaceAll(":\\d+$", "");
        for (String d : DOMINIOS_NGROK) if (h.endsWith(d)) return true;
        Optional<String> manual = c.webhook().ngrokUrl().map(String::trim).filter(s -> !s.isBlank());
        if (manual.isPresent() && h.equals(hostDe(manual.get()))) return true;
        Detectado d = cache;
        return d != null && d.url() != null && h.equals(hostDe(d.url()));
    }

    static String hostDe(String url) {
        try { String u = url.matches("(?i)^https?://.*") ? url : "https://" + url; String h = URI.create(u).getHost(); return h == null ? "" : h.toLowerCase(); }
        catch (Exception e) { return ""; }
    }
}
