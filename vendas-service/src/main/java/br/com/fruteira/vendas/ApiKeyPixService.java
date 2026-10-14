package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Base dos provedores com API própria autenticada por chave/token fixo (sem OAuth2 nem mTLS): Mercado Pago e Asaas.
 * O identificador do pagamento no provedor (id do pagamento) é guardado onde os bancos guardam o endToEndId.
 */
public abstract class ApiKeyPixService implements PixProviderService {
    @Inject protected PixConfig config;
    @Inject protected ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** URL base sem a barra final. */
    protected abstract String baseUrl();
    /** Cabeçalhos de autenticação do provedor. */
    protected abstract Map<String, String> autenticacao();

    protected static String limpa(String url) { return url.endsWith("/") ? url.substring(0, url.length() - 1) : url; }

    protected JsonNode chamar(String metodo, String caminho, Object corpo, Map<String, String> extras) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + caminho)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("Accept", "application/json").header("User-Agent", "FruteiraConventos-PDV");
        autenticacao().forEach(b::header);
        if (extras != null) extras.forEach(b::header);
        b.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(corpo)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException(provider().rotulo() + " respondeu HTTP " + r.statusCode() + " em " + metodo + " " + caminho + ": " + r.body());
        return r.body() == null || r.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(r.body());
    }
}
