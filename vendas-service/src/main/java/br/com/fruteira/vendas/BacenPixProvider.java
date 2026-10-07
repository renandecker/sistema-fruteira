package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Provedor para a API Pix padrão do Banco Central (PSP/banco que expõe /v2/cob...): Efí, Itaú, Bradesco, Asaas e similares.
 * Autenticação OAuth2 (client_credentials) e, se houver certificado .p12, mTLS. ATENÇÃO: escrito conforme a especificação e
 * AINDA NÃO TESTADO contra um PSP real — valide em sandbox. Detalhes variam por PSP (formato do token, escopos, cabeçalhos do webhook).
 * Segredos (client secret, certificado) ficam SÓ no servidor; o React nunca fala com o PSP.
 */
@ApplicationScoped
public class BacenPixProvider implements PixProvider {
    @ConfigProperty(name = "pix.psp.base-url", defaultValue = "") String baseUrl;
    @ConfigProperty(name = "pix.psp.token-url", defaultValue = "") String tokenUrl;
    @ConfigProperty(name = "pix.psp.client-id", defaultValue = "") String clientId;
    @ConfigProperty(name = "pix.psp.client-secret", defaultValue = "") String clientSecret;
    @ConfigProperty(name = "pix.psp.token-formato", defaultValue = "basic-json") String formato;      // basic-json (Efí) | form (Itaú e outros)
    @ConfigProperty(name = "pix.psp.certificado", defaultValue = "") String certificado;              // caminho do .p12 (mTLS)
    @ConfigProperty(name = "pix.psp.certificado-senha", defaultValue = "") String certificadoSenha;
    @ConfigProperty(name = "pix.psp.skip-mtls-webhook", defaultValue = "false") boolean skipMtlsWebhook;
    @ConfigProperty(name = "pix.chave", defaultValue = "") String chave;
    @Inject ObjectMapper mapper;

    private volatile HttpClient http; private String token; private Instant tokenAte = Instant.EPOCH;

    @Override public String nome() { return "psp"; }

    private synchronized HttpClient cliente() throws Exception {
        if (http == null) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
            if (!certificado.isBlank()) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(Path.of(certificado))) { ks.load(in, certificadoSenha.toCharArray()); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, certificadoSenha.toCharArray());
                SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.getKeyManagers(), null, null);
                b.sslContext(ctx);
            }
            http = b.build();
        }
        return http;
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenAte)) return token;
        if (baseUrl.isBlank() || clientId.isBlank()) throw new IllegalStateException("PSP não configurado (PIX_PSP_URL, PIX_PSP_CLIENT_ID, PIX_PSP_CLIENT_SECRET)");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(tokenUrl.isBlank() ? baseUrl + "/oauth/token" : tokenUrl)).timeout(Duration.ofSeconds(15));
        if ("form".equalsIgnoreCase(formato))
            b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(
                "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret) + "&scope=" + enc("cob.write cob.read pix.read pix.write webhook.write")));
        else
            b.header("Content-Type", "application/json")
             .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)))
             .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"client_credentials\"}"));
        HttpResponse<String> r = cliente().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("PSP recusou a autenticação (HTTP " + r.statusCode() + ")");
        JsonNode j = mapper.readTree(r.body());
        token = j.path("access_token").asText();
        tokenAte = Instant.now().plusSeconds(Math.max(30, j.path("expires_in").asLong(600) - 30));
        return token;
    }
    private JsonNode chamar(String metodo, String caminho, Object corpo, Map<String, String> extras) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + caminho)).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token()).header("Content-Type", "application/json");
        if (extras != null) extras.forEach(b::header);
        b.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)));
        HttpResponse<String> r = cliente().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("PSP respondeu HTTP " + r.statusCode() + " em " + metodo + " " + caminho + ": " + r.body());
        return r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
    }
    private static String valor(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    /** PUT /v2/cob/{txid} — cobrança imediata com expiração (calendario.expiracao, em segundos). */
    @Override
    public Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception {
        if (chave.isBlank()) throw new IllegalStateException("Chave Pix não configurada (PIX_CHAVE)");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("calendario", Map.of("expiracao", expiracaoSeg));
        corpo.put("valor", Map.of("original", valor(valor)));
        corpo.put("chave", chave);
        if (descricao != null && !descricao.isBlank()) corpo.put("solicitacaoPagador", descricao.length() > 140 ? descricao.substring(0, 140) : descricao);
        JsonNode r = chamar("PUT", "/v2/cob/" + txid, corpo, null);
        String copiaECola = r.path("pixCopiaECola").asText("");
        String location = r.path("loc").path("location").asText(r.path("location").asText(""));
        if (copiaECola.isBlank() && r.path("loc").hasNonNull("id"))                 // alguns PSPs entregam o payload em /v2/loc/{id}/qrcode
            copiaECola = chamar("GET", "/v2/loc/" + r.path("loc").path("id").asText() + "/qrcode", null, null).path("qrcode").asText("");
        if (copiaECola.isBlank()) throw new IllegalStateException("O PSP não devolveu o Pix Copia e Cola");
        return new Criada(copiaECola, location);
    }
    /** GET /v2/cob/{txid} */
    @Override
    public Situacao consultar(String txid) throws Exception {
        JsonNode r = chamar("GET", "/v2/cob/" + txid, null, null);
        String st = r.path("status").asText("");
        if (st.startsWith("REMOVIDA")) return new Situacao("REMOVIDA", null, null, null);
        JsonNode pix = r.path("pix").isArray() && r.path("pix").size() > 0 ? r.path("pix").get(0) : null;
        if ("CONCLUIDA".equals(st) && pix != null) {
            LocalDateTime h = LocalDateTime.now();
            try { h = OffsetDateTime.parse(pix.path("horario").asText()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignorada) { /* usa agora */ }
            return new Situacao("CONCLUIDA", pix.path("endToEndId").asText(null), new BigDecimal(pix.path("valor").asText("0")), h);
        }
        return new Situacao("ATIVA", null, null, null);
    }
    /** PATCH /v2/cob/{txid} */
    @Override public void cancelar(String txid) throws Exception { chamar("PATCH", "/v2/cob/" + txid, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR"), null); }
    /** PUT /v2/pix/{e2eid}/devolucao/{id} */
    @Override public void devolver(String e2e, String idDevolucao, BigDecimal valor) throws Exception { chamar("PUT", "/v2/pix/" + e2e + "/devolucao/" + idDevolucao, Map.of("valor", valor(valor)), null); }
    /** PUT /v2/webhook/{chave} */
    @Override
    public void registrarWebhook(String url) throws Exception {
        chamar("PUT", "/v2/webhook/" + enc(chave), Map.of("webhookUrl", url), skipMtlsWebhook ? Map.of("x-skip-mtls-checking", "true") : null);
    }
}
