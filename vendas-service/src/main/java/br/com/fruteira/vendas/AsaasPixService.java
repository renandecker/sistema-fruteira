package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pix via Asaas: cobrança PIX (POST /v3/payments) + QR Code (GET /v3/payments/{id}/pixQrCode). Configuração: PIX_ASAAS_*.
 * - Toda cobrança é feita no cliente "Consumidor final" (PIX_ASAAS_CLIENTE_ID), criado uma vez no painel.
 * - A cobrança é ligada ao nosso txid por externalReference; o id do pagamento (pay_...) é guardado como "endToEndId" e usado na devolução.
 * - A validade do QR no Asaas é longa (vence 12 meses após o vencimento): o caixa expira antes e o sistema exclui a cobrança no Asaas.
 * - Webhook: eventos PAYMENT_RECEIVED/PAYMENT_CONFIRMED com {"event":..,"payment":{"id","externalReference","value"}}.
 * ATENÇÃO: escrito conforme a documentação e AINDA NÃO TESTADO contra a conta real — valide no sandbox (api-sandbox.asaas.com).
 */
@ApplicationScoped
public class AsaasPixService extends ApiKeyPixService {
    @Override public PixProvider provider() { return PixProvider.ASAAS; }
    @Override protected String baseUrl() { return limpa(config.asaas().baseUrl().filter(s -> !s.isBlank()).orElse(provider().urlPadrao())); }
    @Override protected String caminhoDeTeste() { return "/finance/balance"; }
    @Override protected Map<String, String> autenticacao() { return Map.of("access_token", config.asaas().apiKey().orElse("")); }

    @Override
    public List<String> problemasDeConfiguracao() {
        List<String> l = new ArrayList<>();
        if (config.asaas().apiKey().filter(s -> !s.isBlank()).isEmpty()) l.add("Informe PIX_ASAAS_API_KEY (chave de API do Asaas)");
        if (config.asaas().clienteId().filter(s -> !s.isBlank()).isEmpty()) l.add("Informe PIX_ASAAS_CLIENTE_ID (id cus_... do cliente \"Consumidor final\" criado no Asaas)");
        return l;
    }

    /** POST /v3/payments (billingType PIX) e GET /v3/payments/{id}/pixQrCode */
    @Override
    public Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("customer", config.asaas().clienteId().orElse(""));
        corpo.put("billingType", "PIX");
        corpo.put("value", valor.setScale(2, RoundingMode.HALF_UP));
        corpo.put("dueDate", LocalDate.now().toString());
        corpo.put("externalReference", txid);
        corpo.put("description", descricao == null || descricao.isBlank() ? "Venda Fruteira Conventos" : descricao);
        JsonNode cob = chamar("POST", "/payments", corpo, null);
        String id = cob.path("id").asText("");
        if (id.isBlank()) throw new IllegalStateException("O Asaas não devolveu o id da cobrança");
        JsonNode qr = chamar("GET", "/payments/" + id + "/pixQrCode", null, null);
        String copiaECola = qr.path("payload").asText("");
        if (copiaECola.isBlank()) throw new IllegalStateException("O Asaas não devolveu o Pix Copia e Cola");
        return new Criada(copiaECola, cob.path("invoiceUrl").asText(""));
    }

    /** Cobrança com este externalReference (null = não achou). */
    private JsonNode buscar(String txid) throws Exception {
        JsonNode r = chamar("GET", "/payments?externalReference=" + txid, null, null);
        JsonNode lista = r.path("data");
        return lista.isArray() && lista.size() > 0 ? lista.get(0) : null;
    }

    /** PENDING = ATIVA; RECEIVED/CONFIRMED = CONCLUIDA; OVERDUE = EXPIRADA; excluída/estornada/etc. = removida. */
    @Override
    public Situacao consultar(String txid) throws Exception {
        JsonNode p = buscar(txid);
        if (p == null) return new Situacao(PixStatus.ATIVA, null, null, null);
        if (p.path("deleted").asBoolean(false)) return new Situacao(PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR, null, null, null);
        switch (p.path("status").asText("")) {
            case "RECEIVED", "CONFIRMED", "RECEIVED_IN_CASH", "REFUNDED", "REFUND_REQUESTED" -> {
                LocalDateTime h = LocalDateTime.now();
                try { h = LocalDate.parse(p.path("paymentDate").asText(p.path("confirmedDate").asText())).atStartOfDay(); } catch (Exception ignorada) { /* usa agora */ }
                return new Situacao(PixStatus.CONCLUIDA, p.path("id").asText(null), new BigDecimal(p.path("value").asText("0")), h);
            }
            case "OVERDUE" -> { return new Situacao(PixStatus.EXPIRADA, null, null, null); }
            default -> { return new Situacao(PixStatus.ATIVA, null, null, null); }
        }
    }

    /** DELETE /v3/payments/{id} */
    @Override
    public void cancelar(String txid) throws Exception {
        JsonNode p = buscar(txid);
        if (p != null) chamar("DELETE", "/payments/" + p.path("id").asText(), null, null);
    }
    /** POST /v3/payments/{id}/refund — o "endToEndId" guardado é o id do pagamento. */
    @Override
    public void devolver(String idPagamento, String idDevolucao, BigDecimal valor) throws Exception {
        chamar("POST", "/payments/" + idPagamento + "/refund", Map.of("value", valor.setScale(2, RoundingMode.HALF_UP), "description", "Devolução " + idDevolucao), null);
    }
    /** POST /v3/webhooks (também pode ser feito no painel: Integrações → Webhooks). */
    @Override
    public void registrarWebhook(String url) throws Exception {
        String email = config.asaas().emailWebhook().filter(s -> !s.isBlank()).orElseThrow(() -> new IllegalStateException("Defina PIX_ASAAS_EMAIL_WEBHOOK (ou cadastre o webhook no painel do Asaas)"));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("name", "Fruteira Conventos - Pix"); corpo.put("url", url); corpo.put("email", email);
        corpo.put("enabled", true); corpo.put("interrupted", false); corpo.put("apiVersion", 3); corpo.put("sendType", "SEQUENTIALLY");
        corpo.put("events", List.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED"));
        chamar("POST", "/webhooks", corpo, null);
    }

    /** {"event":"PAYMENT_RECEIVED","payment":{"id":"pay_..","externalReference":"<txid>","value":12.5}} */
    @Override
    public List<Notificacao> interpretarWebhook(JsonNode corpo) {
        List<Notificacao> l = new ArrayList<>();
        if (corpo == null) return l;
        String evento = corpo.path("event").asText("");
        JsonNode p = corpo.path("payment");
        String txid = p.path("externalReference").asText(""), id = p.path("id").asText("");
        if ((evento.equals("PAYMENT_RECEIVED") || evento.equals("PAYMENT_CONFIRMED")) && !txid.isBlank() && !id.isBlank())
            l.add(new Notificacao(txid, id, new BigDecimal(p.path("value").asText("0")), LocalDateTime.now()));
        return l;
    }
}
