package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Base dos provedores que expõem a API Pix padrão do Banco Central (/v2/cob...): Efí, Itaú, Bradesco, Sicoob e Sicredi.
 * Cada banco é uma subclasse mínima que informa o {@link PixProvider} e o seu grupo em {@link PixConfig}; quirks de um banco
 * específico (cabeçalhos, caminhos) entram sobrescrevendo métodos aqui.
 * Autenticação OAuth2 (client_credentials) e mTLS com certificado .p12. ATENÇÃO: escrito conforme a especificação e AINDA NÃO
 * TESTADO contra um PSP real — valide no sandbox de cada banco (formato do token, escopos, URLs e cabeçalhos do webhook variam).
 * Segredos (client secret, certificado) ficam SÓ no servidor; o React nunca fala com o PSP.
 */
public abstract class BacenPixService implements PixProviderService {
    private static final String ESCOPOS_PADRAO = "cob.write cob.read pix.read pix.write webhook.write webhook.read";
    @Inject protected PixConfig config;
    @Inject protected ObjectMapper mapper;

    /** Grupo de configuração deste provedor (pix.efi.*, pix.itau.*...). */
    protected abstract PixConfig.Psp psp();

    private volatile HttpClient http; private String token; private Instant tokenAte = Instant.EPOCH;

    protected String baseUrl() { return psp().baseUrl().filter(s -> !s.isBlank()).orElse(provider().urlPadrao() == null ? "" : provider().urlPadrao()); }
    protected String tokenUrl() {
        return psp().tokenUrl().filter(s -> !s.isBlank()).orElse(provider().tokenUrlPadrao() != null ? provider().tokenUrlPadrao() : baseUrl() + "/oauth/token");
    }
    protected String clientId() { return psp().clientId().orElse(""); }
    protected String clientSecret() { return psp().clientSecret().orElse(""); }
    protected String certificado() { return psp().certificado().orElse(""); }
    protected String certificadoSenha() { return psp().certificadoSenha().orElse(""); }
    protected String chave() { return psp().chave().filter(s -> !s.isBlank()).orElse(config.chave().orElse("")); }
    /** Prefixo da versão da API no caminho: o Bacen usa /v2/cob, mas alguns bancos já trazem a versão na URL base (Sicoob) ou usam /api/v2 (Sicredi). */
    protected String prefixoApiPadrao() { return "/v2"; }
    /** PIX_<BANCO>_PREFIXO_API sobrescreve; "-" = sem prefixo. */
    protected String prefixoApi() {
        return psp().prefixoApi().map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.equals("-") ? "" : s).orElse(prefixoApiPadrao());
    }
    /** Cobrança: {prefixo}/cob (ou /cob-emv, conforme PIX_<BANCO>_CAMINHO_COB). */
    protected String caminhoCob() { return prefixoApi() + psp().caminhoCob().filter(s -> !s.isBlank()).orElse("/cob"); }
    protected String escopos() { return psp().escopos().filter(s -> !s.isBlank()).orElse(escoposPadrao()); }
    /** Escopos quando PIX_<BANCO>_ESCOPOS não é informado; o banco que exige outros sobrescreve. */
    protected String escoposPadrao() { return ESCOPOS_PADRAO; }
    /** Cabeçalhos que ESTE banco exige em todas as chamadas (ex.: Sicoob → client_id); sobrescreva na subclasse. */
    protected Map<String, String> cabecalhosDoBanco() { return Map.of(); }
    /** Cabeçalhos do banco + os de PIX_<BANCO>_CABECALHOS (estes vencem). */
    protected Map<String, String> cabecalhos() {
        Map<String, String> m = new LinkedHashMap<>(cabecalhosDoBanco());
        m.putAll(PixUtil.parseCabecalhos(psp().cabecalhos().orElse("")));
        return m;
    }
    protected String formatoToken() { return psp().tokenFormato().filter(s -> !s.isBlank()).orElse(provider().formatoTokenPadrao()); }

    @Override
    public List<String> problemasDeConfiguracao() {
        String p = provider().prefixoEnv();
        List<String> l = new ArrayList<>();
        if (baseUrl().isBlank()) l.add("Informe " + p + "URL (URL base da API Pix do " + provider().rotulo() + ")");
        if (clientId().isBlank()) l.add("Informe " + p + "CLIENT_ID");
        if (clientSecret().isBlank()) l.add("Informe " + p + "CLIENT_SECRET");
        if (chave().isBlank()) l.add("Informe PIX_CHAVE ou " + p + "CHAVE (chave Pix cadastrada no " + provider().rotulo() + ")");
        if (certificado().isBlank()) l.add("Informe " + p + "CERTIFICADO (.p12 do mTLS) e " + p + "CERTIFICADO_SENHA");
        else if (!Files.isReadable(Path.of(certificado()))) l.add("Certificado não encontrado ou sem permissão de leitura: " + certificado());
        return l;
    }

    private synchronized HttpClient cliente() throws Exception {
        if (http == null) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
            if (!certificado().isBlank()) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(Path.of(certificado()))) { ks.load(in, certificadoSenha().toCharArray()); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, certificadoSenha().toCharArray());
                SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.getKeyManagers(), null, null);
                b.sslContext(ctx);
            }
            http = b.build();
        }
        return http;
    }
    protected static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Autentica de verdade (ignora o token em cache): prova que certificado, client id/secret e URLs estão certos. */
    @Override public synchronized void testarConexao() throws Exception { tokenAte = Instant.EPOCH; token(); }

    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenAte)) return token;
        if (baseUrl().isBlank() || clientId().isBlank()) throw new IllegalStateException("Pix " + provider().rotulo() + " não configurado: " + String.join("; ", problemasDeConfiguracao()));
        String basic = "Basic " + Base64.getEncoder().encodeToString((clientId() + ":" + clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(tokenUrl())).timeout(Duration.ofSeconds(15));
        cabecalhos().forEach(b::header);
        switch (formatoToken().toLowerCase()) {
            case "form" ->                                    // credenciais no corpo (Itaú, Sicoob)
                b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&client_id=" + enc(clientId()) + "&client_secret=" + enc(clientSecret()) + "&scope=" + enc(escopos())));
            case "basic-form" ->                              // Basic no cabeçalho + corpo de formulário (Bradesco, Sicredi)
                b.header("Content-Type", "application/x-www-form-urlencoded").header("Authorization", basic)
                 .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + enc(escopos())));
            default ->                                        // basic-json (Efí)
                b.header("Content-Type", "application/json").header("Authorization", basic)
                 .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"client_credentials\"}"));
        }
        HttpResponse<String> r = cliente().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException(provider().rotulo() + " recusou a autenticação (HTTP " + r.statusCode() + ")");
        JsonNode j = mapper.readTree(r.body());
        token = j.path("access_token").asText();
        tokenAte = Instant.now().plusSeconds(Math.max(30, j.path("expires_in").asLong(600) - 30));
        return token;
    }
    private JsonNode chamar(String metodo, String caminho, Object corpo, Map<String, String> extras) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + caminho)).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token()).header("Content-Type", "application/json");
        cabecalhos().forEach(b::header);
        if (extras != null) extras.forEach(b::header);
        b.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)));
        HttpResponse<String> r = cliente().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException(provider().rotulo() + " respondeu HTTP " + r.statusCode() + " em " + metodo + " " + caminho + ": " + r.body());
        return r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
    }
    private static String valor(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    /** PUT {prefixo}/cob/{txid} — cobrança imediata com expiração (calendario.expiracao, em segundos). */
    @Override
    public Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception {
        if (chave().isBlank()) throw new IllegalStateException("Chave Pix não configurada (PIX_CHAVE ou " + provider().prefixoEnv() + "CHAVE)");
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("calendario", Map.of("expiracao", expiracaoSeg));
        corpo.put("valor", Map.of("original", valor(valor)));
        corpo.put("chave", chave());
        if (descricao != null && !descricao.isBlank()) corpo.put("solicitacaoPagador", descricao.length() > 140 ? descricao.substring(0, 140) : descricao);
        JsonNode r = chamar("PUT", caminhoCob() + "/" + txid, corpo, null);
        String copiaECola = r.path("pixCopiaECola").asText(r.path("emv").asText(""));   // cob-emv devolve "emv"
        String location = r.path("loc").path("location").asText(r.path("location").asText(""));
        if (copiaECola.isBlank() && r.path("loc").hasNonNull("id"))                 // alguns PSPs entregam o payload em /v2/loc/{id}/qrcode
            copiaECola = chamar("GET", prefixoApi() + "/loc/" + r.path("loc").path("id").asText() + "/qrcode", null, null).path("qrcode").asText("");
        if (copiaECola.isBlank()) throw new IllegalStateException("O PSP não devolveu o Pix Copia e Cola");
        return new Criada(copiaECola, location);
    }
    /** GET /v2/cob/{txid} */
    @Override
    public Situacao consultar(String txid) throws Exception {
        JsonNode r = chamar("GET", caminhoCob() + "/" + txid, null, null);
        PixStatus st = PixStatus.deTexto(r.path("status").asText(""));
        JsonNode pix = r.path("pix").isArray() && r.path("pix").size() > 0 ? r.path("pix").get(0) : null;
        if (st == PixStatus.CONCLUIDA && pix != null) {
            LocalDateTime h = LocalDateTime.now();
            try { h = OffsetDateTime.parse(pix.path("horario").asText()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignorada) { /* usa agora */ }
            return new Situacao(PixStatus.CONCLUIDA, pix.path("endToEndId").asText(null), new BigDecimal(pix.path("valor").asText("0")), h);
        }
        if (st == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR || st == PixStatus.EXPIRADA) return new Situacao(st, null, null, null);
        return new Situacao(PixStatus.ATIVA, null, null, null);
    }
    /** PATCH /v2/cob/{txid} */
    @Override public void cancelar(String txid) throws Exception { chamar("PATCH", caminhoCob() + "/" + txid, Map.of("status", PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR.name()), null); }
    /** PUT /v2/pix/{e2eid}/devolucao/{id} */
    @Override public void devolver(String e2e, String idDevolucao, BigDecimal valor) throws Exception { chamar("PUT", prefixoApi() + "/pix/" + e2e + "/devolucao/" + idDevolucao, Map.of("valor", valor(valor)), null); }
    /** PUT /v2/webhook/{chave} */
    @Override
    public void registrarWebhook(String url) throws Exception {
        chamar("PUT", prefixoApi() + "/webhook/" + enc(chave()), Map.of("webhookUrl", url), psp().skipMtlsWebhook() ? Map.of("x-skip-mtls-checking", "true") : null);
    }
}
