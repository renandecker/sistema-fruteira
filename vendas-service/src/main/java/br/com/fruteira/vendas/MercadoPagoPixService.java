package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Pix via Mercado Pago (Payments API: POST /v1/payments com payment_method_id "pix"). Configuração: PIX_MERCADO_PAGO_*.
 * - A cobrança é ligada ao nosso txid por external_reference (a consulta/cancelamento procura por ele).
 * - O identificador do pagamento (id do MP) é guardado como "endToEndId" e é o que a devolução usa.
 * - O Pix do Mercado Pago dura no mínimo 30 minutos: o caixa expira antes (PIX_EXPIRACAO_SEG) e o sistema cancela a cobrança no MP.
 * - O webhook recebe só o id do pagamento ({"action":"payment.updated","data":{"id":"..."}}); consultamos o pagamento para confirmar.
 *   A URL (FRUTEIRA_PIX_WEBHOOK_URL ou a do Ngrok, com ?token=; veja PixWebhook) vai em cada cobrança como notification_url: não há "registrar webhook".
 * ATENÇÃO: escrito conforme a documentação e AINDA NÃO TESTADO contra a conta real — valide com as credenciais de teste.
 */
@ApplicationScoped
public class MercadoPagoPixService extends ApiKeyPixService {
    private static final Logger LOG = Logger.getLogger(MercadoPagoPixService.class);
    private static final DateTimeFormatter DATA_MP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    @Override public PixProvider provider() { return PixProvider.MERCADO_PAGO; }
    @Override protected String baseUrl() { return limpa(config.mercadoPago().baseUrl().filter(s -> !s.isBlank()).orElse(provider().urlPadrao())); }
    @Override protected Map<String, String> autenticacao() { return Map.of("Authorization", "Bearer " + config.mercadoPago().accessToken().orElse("")); }

    @Override
    public List<String> problemasDeConfiguracao() {
        List<String> l = new ArrayList<>();
        if (config.mercadoPago().accessToken().filter(s -> !s.isBlank()).isEmpty()) l.add("Informe PIX_MERCADO_PAGO_ACCESS_TOKEN (Access Token da aplicação)");
        if (config.mercadoPago().emailPagador().filter(s -> !s.isBlank()).isEmpty()) l.add("Informe PIX_MERCADO_PAGO_EMAIL_PAGADOR (e-mail genérico do pagador exigido pela API)");
        if (PixWebhook.url(config).isEmpty()) l.add("Informe FRUTEIRA_PIX_WEBHOOK_URL ou use o Ngrok (NGROK_URL, ou deixe o Ngrok aberto na porta 8083). A URL vai em cada cobrança; sem ela o pagamento só é percebido pela consulta a cada 10 s");
        return l;
    }

    /** POST /v1/payments */
    @Override
    public Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception {
        Map<String, Object> pagador = new LinkedHashMap<>();
        pagador.put("email", config.mercadoPago().emailPagador().orElse(""));
        config.mercadoPago().cpfPagador().filter(s -> !s.isBlank()).ifPresent(cpf -> pagador.put("identification", Map.of("type", "CPF", "number", cpf.replaceAll("\\D", ""))));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("transaction_amount", valor.setScale(2, RoundingMode.HALF_UP));
        corpo.put("description", descricao == null || descricao.isBlank() ? "Venda Fruteira Conventos" : descricao);
        corpo.put("payment_method_id", "pix");
        corpo.put("payer", pagador);
        corpo.put("external_reference", txid);
        corpo.put("date_of_expiration", OffsetDateTime.now().plusSeconds(Math.max(expiracaoSeg, 1800)).format(DATA_MP));   // mínimo de 30 min no MP
        PixWebhook.url(config).ifPresent(u -> corpo.put("notification_url", u));   // URL fixa ou do Ngrok (veja PixWebhook)
        JsonNode r = chamar("POST", "/v1/payments", corpo, Map.of("X-Idempotency-Key", txid));
        JsonNode dados = r.path("point_of_interaction").path("transaction_data");
        String copiaECola = dados.path("qr_code").asText("");
        if (copiaECola.isBlank()) throw new IllegalStateException("O Mercado Pago não devolveu o Pix Copia e Cola");
        return new Criada(copiaECola, dados.path("ticket_url").asText(""));
    }

    /** Pagamento mais recente com este external_reference (null = não achou). */
    private JsonNode buscar(String txid) throws Exception {
        JsonNode r = chamar("GET", "/v1/payments/search?sort=date_created&criteria=desc&external_reference=" + txid, null, null);
        JsonNode lista = r.path("results");
        return lista.isArray() && lista.size() > 0 ? lista.get(0) : null;
    }

    /** GET /v1/payments/search?external_reference= — pending/in_process = ATIVA; approved = CONCLUIDA; cancelled = removida (ou expirada). */
    @Override
    public Situacao consultar(String txid) throws Exception {
        JsonNode p = buscar(txid);
        if (p == null) return new Situacao(PixStatus.ATIVA, null, null, null);
        return situacao(p);
    }
    static Situacao situacao(JsonNode p) {
        String st = p.path("status").asText(""), detalhe = p.path("status_detail").asText("");
        switch (st) {
            case "approved", "authorized", "refunded", "charged_back" -> {      // o dinheiro entrou
                LocalDateTime h = LocalDateTime.now();
                try { h = OffsetDateTime.parse(p.path("date_approved").asText()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignorada) { /* usa agora */ }
                return new Situacao(PixStatus.CONCLUIDA, p.path("id").asText(null), new BigDecimal(p.path("transaction_amount").asText("0")), h);
            }
            case "cancelled", "rejected" -> { return new Situacao("expired".equals(detalhe) ? PixStatus.EXPIRADA : PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR, null, null, null); }
            default -> { return new Situacao(PixStatus.ATIVA, null, null, null); }   // pending, in_process...
        }
    }

    /** PUT /v1/payments/{id} {"status":"cancelled"} */
    @Override
    public void cancelar(String txid) throws Exception {
        JsonNode p = buscar(txid);
        if (p == null) return;
        chamar("PUT", "/v1/payments/" + p.path("id").asText(), Map.of("status", "cancelled"), null);
    }
    /** POST /v1/payments/{id}/refunds — o "endToEndId" guardado é o id do pagamento. */
    @Override
    public void devolver(String idPagamento, String idDevolucao, BigDecimal valor) throws Exception {
        chamar("POST", "/v1/payments/" + idPagamento + "/refunds", Map.of("amount", valor.setScale(2, RoundingMode.HALF_UP)), Map.of("X-Idempotency-Key", idDevolucao));
    }
    @Override
    public void registrarWebhook(String url) {
        throw new UnsupportedOperationException("O Mercado Pago recebe a URL em cada cobrança (notification_url): defina FRUTEIRA_PIX_WEBHOOK_URL. Para outras notificações, configure em Suas integrações → Webhooks no painel do Mercado Pago.");
    }

    /** Notificação {"action":"payment.updated","type":"payment","data":{"id":"123"}}: confirma consultando o pagamento. */
    @Override
    public List<Notificacao> interpretarWebhook(JsonNode corpo) {
        List<Notificacao> l = new ArrayList<>();
        if (corpo == null) return l;
        String tipo = corpo.path("type").asText(corpo.path("topic").asText("payment"));
        String id = corpo.path("data").path("id").asText(corpo.path("id").asText(""));
        if (!"payment".equals(tipo) || id.isBlank()) return l;
        try {
            JsonNode p = chamar("GET", "/v1/payments/" + id, null, null);
            Situacao s = situacao(p);
            String txid = p.path("external_reference").asText("");
            if (s.status() == PixStatus.CONCLUIDA && !txid.isBlank() && "pix".equals(p.path("payment_method_id").asText("pix")))
                l.add(new Notificacao(txid, id, s.valor(), s.horario()));
        } catch (Exception e) { LOG.warn("Mercado Pago: não foi possível confirmar o pagamento " + id + ": " + e.getMessage()); }
        return l;
    }
}
