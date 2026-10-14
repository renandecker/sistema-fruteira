package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * Roda as integrações reais (Mercado Pago, Asaas, Sicoob, Efí) contra um servidor HTTP local que imita o provedor.
 * Só JDK + Jackson + stubs das anotações: ./testar-pix.sh. NÃO prova compatibilidade com o provedor real (isso é no sandbox).
 */
public class PixProvidersTeste {
    static int ok = 0, falhas = 0;
    static void confere(String nome, boolean c) { if (c) ok++; else { falhas++; System.out.println("FALHOU: " + nome); } }
    static final ObjectMapper M = new ObjectMapper();

    record Req(String metodo, String caminho, Map<String, String> h, String corpo) {
        String cab(String n) { for (var e : h.entrySet()) if (e.getKey().equalsIgnoreCase(n)) return e.getValue(); return null; }
        JsonNode json() throws Exception { return M.readTree(corpo); }
    }
    static class Falso {
        final HttpServer srv; final List<Req> reqs = new ArrayList<>(); volatile Function<Req, String> resp = r -> "{}";
        Falso() throws Exception {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/", ex -> {
                Map<String, String> h = new HashMap<>(); ex.getRequestHeaders().forEach((k, v) -> h.put(k, v.get(0)));
                String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Req r = new Req(ex.getRequestMethod(), ex.getRequestURI().toString(), h, corpo); reqs.add(r);
                byte[] b = resp.apply(r).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.length == 0 ? -1 : b.length);
                if (b.length > 0) ex.getResponseBody().write(b); ex.close();
            });
            srv.start();
        }
        String url() { return "http://127.0.0.1:" + srv.getAddress().getPort(); }
        Req ultima() { return reqs.get(reqs.size() - 1); }
        void limpa() { reqs.clear(); }
    }

    /** PixConfig de mentira: valores por "grupo.metodo"; Optional vazio, false, 0 e "" por padrão. */
    @SuppressWarnings("unchecked")
    static <T> T cfg(Class<T> c, String pre, Map<String, Object> v) {
        return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[] { c }, (p, m, a) -> {
            String k = pre + m.getName(); Class<?> r = m.getReturnType();
            if (v.containsKey(k)) return r == Optional.class ? Optional.ofNullable(v.get(k)) : v.get(k);
            if (r == Optional.class) return Optional.empty();
            if (r.isInterface()) return cfg(r, k + ".", v);
            if (r == boolean.class) return false; if (r == int.class) return 0; if (r == String.class) return "";
            return null;
        });
    }
    static PixConfig config(Map<String, Object> v) { return cfg(PixConfig.class, "", v); }
    static <S extends PixProviderService> S monta(S s, Map<String, Object> v) throws Exception {
        var c = s instanceof BacenPixService ? BacenPixService.class : ApiKeyPixService.class;
        var f = c.getDeclaredField("config"); f.setAccessible(true); f.set(s, config(v));
        var g = c.getDeclaredField("mapper"); g.setAccessible(true); g.set(s, M);
        return s;
    }
    static boolean erro(Class<? extends Throwable> t, java.util.concurrent.Callable<?> r) { try { r.call(); return false; } catch (Throwable e) { return t.isInstance(e); } }

    public static void main(String[] a) throws Exception {
        mercadoPago(); asaas(); sicoob(); efi();
        System.out.println(ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- Mercado Pago
    static void mercadoPago() throws Exception {
        Falso f = new Falso();
        Map<String, Object> v = new HashMap<>(Map.of("mercadoPago.baseUrl", f.url(), "mercadoPago.accessToken", "TOK", "mercadoPago.emailPagador", "loja@x.com", "webhook.url", "https://x/api/vendas/pix/webhook?token=S"));
        MercadoPagoPixService s = monta(new MercadoPagoPixService(), v);
        confere("MP: provider", s.provider() == PixProvider.MERCADO_PAGO);
        confere("MP: sem problemas", s.problemasDeConfiguracao().isEmpty());
        confere("MP: problemas quando vazio", monta(new MercadoPagoPixService(), Map.of()).problemasDeConfiguracao().size() == 3);

        f.resp = r -> "{\"id\":123,\"status\":\"pending\",\"point_of_interaction\":{\"transaction_data\":{\"qr_code\":\"00020126MP\",\"ticket_url\":\"https://t\"}}}";
        var c = s.criar("tx1", new BigDecimal("12.50"), 300, "Venda 1");
        Req r = f.ultima(); JsonNode j = r.json();
        confere("MP criar: POST /v1/payments", r.metodo().equals("POST") && r.caminho().equals("/v1/payments"));
        confere("MP criar: Bearer", "Bearer TOK".equals(r.cab("Authorization")));
        confere("MP criar: idempotência = txid", "tx1".equals(r.cab("X-Idempotency-Key")));
        confere("MP criar: valor e método", j.path("transaction_amount").decimalValue().compareTo(new BigDecimal("12.5")) == 0 && j.path("payment_method_id").asText().equals("pix"));
        confere("MP criar: external_reference", j.path("external_reference").asText().equals("tx1"));
        confere("MP criar: notification_url", j.path("notification_url").asText().endsWith("token=S"));
        confere("MP criar: e-mail do pagador", j.path("payer").path("email").asText().equals("loja@x.com"));
        confere("MP criar: expira em >= 30 min", OffsetDateTime.parse(j.path("date_of_expiration").asText()).isAfter(OffsetDateTime.now().plusMinutes(29)));
        confere("MP criar: copia e cola", c.pixCopiaECola().equals("00020126MP"));

        f.resp = q -> "{\"results\":[{\"id\":123,\"status\":\"approved\",\"transaction_amount\":12.5,\"date_approved\":\"2026-10-14T00:10:00.000-03:00\"}]}";
        var st = s.consultar("tx1");
        confere("MP consultar: busca por external_reference", f.ultima().caminho().contains("external_reference=tx1"));
        confere("MP consultar: approved = CONCLUIDA com id", st.status() == PixStatus.CONCLUIDA && "123".equals(st.endToEndId()) && st.valor().compareTo(new BigDecimal("12.5")) == 0);
        f.resp = q -> "{\"results\":[{\"id\":123,\"status\":\"pending\"}]}";
        confere("MP consultar: pending = ATIVA", s.consultar("tx1").status() == PixStatus.ATIVA);
        f.resp = q -> "{\"results\":[{\"id\":123,\"status\":\"cancelled\",\"status_detail\":\"expired\"}]}";
        confere("MP consultar: cancelled/expired = EXPIRADA", s.consultar("tx1").status() == PixStatus.EXPIRADA);
        f.resp = q -> "{\"results\":[{\"id\":123,\"status\":\"cancelled\"}]}";
        confere("MP consultar: cancelled = REMOVIDA", s.consultar("tx1").status() == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR);
        f.resp = q -> "{\"results\":[]}";
        confere("MP consultar: sem resultado = ATIVA", s.consultar("tx1").status() == PixStatus.ATIVA);

        f.limpa(); f.resp = q -> q.metodo().equals("GET") ? "{\"results\":[{\"id\":123,\"status\":\"pending\"}]}" : "{}";
        s.cancelar("tx1");
        r = f.ultima();
        confere("MP cancelar: PUT /v1/payments/123 cancelled", r.metodo().equals("PUT") && r.caminho().equals("/v1/payments/123") && r.json().path("status").asText().equals("cancelled"));

        f.resp = q -> "{}"; s.devolver("123", "D1", new BigDecimal("12.50")); r = f.ultima();
        confere("MP devolver: POST refunds com valor e idempotência", r.caminho().equals("/v1/payments/123/refunds") && r.json().path("amount").decimalValue().compareTo(new BigDecimal("12.5")) == 0 && "D1".equals(r.cab("X-Idempotency-Key")));

        f.resp = q -> "{\"id\":123,\"status\":\"approved\",\"external_reference\":\"tx1\",\"transaction_amount\":12.5,\"payment_method_id\":\"pix\",\"date_approved\":\"2026-10-14T00:10:00.000-03:00\"}";
        var n = s.interpretarWebhook(M.readTree("{\"action\":\"payment.updated\",\"type\":\"payment\",\"data\":{\"id\":\"123\"}}"));
        confere("MP webhook: confirma consultando o pagamento", n.size() == 1 && n.get(0).txid().equals("tx1") && n.get(0).endToEndId().equals("123") && f.ultima().caminho().equals("/v1/payments/123"));
        f.resp = q -> "{\"id\":123,\"status\":\"pending\",\"external_reference\":\"tx1\"}";
        confere("MP webhook: pagamento pendente não conta", s.interpretarWebhook(M.readTree("{\"type\":\"payment\",\"data\":{\"id\":\"123\"}}")).isEmpty());
        int antes = f.reqs.size();
        confere("MP webhook: outro tipo é ignorado sem chamar a API", s.interpretarWebhook(M.readTree("{\"type\":\"plan\",\"data\":{\"id\":\"9\"}}")).isEmpty() && f.reqs.size() == antes);
        confere("MP: registrarWebhook explica", erro(UnsupportedOperationException.class, () -> { s.registrarWebhook("u"); return null; }));
        f.srv.stop(0);
    }

    // ---------------------------------------------------------------- Asaas
    static void asaas() throws Exception {
        Falso f = new Falso();
        Map<String, Object> v = new HashMap<>(Map.of("asaas.baseUrl", f.url() + "/v3/", "asaas.apiKey", "K", "asaas.clienteId", "cus_1"));
        AsaasPixService s = monta(new AsaasPixService(), v);
        confere("Asaas: sem problemas", s.problemasDeConfiguracao().isEmpty());
        confere("Asaas: problemas quando vazio", monta(new AsaasPixService(), Map.of()).problemasDeConfiguracao().size() == 2);

        f.resp = r -> r.caminho().endsWith("pixQrCode") ? "{\"payload\":\"00020126AS\",\"expirationDate\":\"2027-10-14\"}" : "{\"id\":\"pay_1\",\"invoiceUrl\":\"https://i\"}";
        var c = s.criar("tx1", new BigDecimal("12.50"), 300, "Venda 1");
        Req r1 = f.reqs.get(0), r2 = f.reqs.get(1); JsonNode j = r1.json();
        confere("Asaas criar: POST /v3/payments", r1.metodo().equals("POST") && r1.caminho().equals("/v3/payments"));
        confere("Asaas criar: access_token", "K".equals(r1.cab("access_token")) && r1.cab("User-Agent") != null);
        confere("Asaas criar: corpo", j.path("customer").asText().equals("cus_1") && j.path("billingType").asText().equals("PIX") && j.path("externalReference").asText().equals("tx1")
            && j.path("value").decimalValue().compareTo(new BigDecimal("12.5")) == 0 && j.path("dueDate").asText().equals(java.time.LocalDate.now().toString()));
        confere("Asaas criar: busca o QR", r2.metodo().equals("GET") && r2.caminho().equals("/v3/payments/pay_1/pixQrCode"));
        confere("Asaas criar: copia e cola", c.pixCopiaECola().equals("00020126AS"));

        f.resp = q -> "{\"data\":[{\"id\":\"pay_1\",\"status\":\"RECEIVED\",\"value\":12.5,\"paymentDate\":\"2026-10-14\"}]}";
        var st = s.consultar("tx1");
        confere("Asaas consultar: filtra por externalReference", f.ultima().caminho().equals("/v3/payments?externalReference=tx1"));
        confere("Asaas consultar: RECEIVED = CONCLUIDA", st.status() == PixStatus.CONCLUIDA && "pay_1".equals(st.endToEndId()) && st.valor().compareTo(new BigDecimal("12.5")) == 0);
        f.resp = q -> "{\"data\":[{\"id\":\"pay_1\",\"status\":\"PENDING\"}]}";
        confere("Asaas consultar: PENDING = ATIVA", s.consultar("tx1").status() == PixStatus.ATIVA);
        f.resp = q -> "{\"data\":[{\"id\":\"pay_1\",\"status\":\"OVERDUE\"}]}";
        confere("Asaas consultar: OVERDUE = EXPIRADA", s.consultar("tx1").status() == PixStatus.EXPIRADA);
        f.resp = q -> "{\"data\":[{\"id\":\"pay_1\",\"status\":\"PENDING\",\"deleted\":true}]}";
        confere("Asaas consultar: excluída = REMOVIDA", s.consultar("tx1").status() == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR);

        f.limpa(); f.resp = q -> "{\"data\":[{\"id\":\"pay_1\",\"status\":\"PENDING\"}]}";
        s.cancelar("tx1");
        confere("Asaas cancelar: DELETE /v3/payments/pay_1", f.ultima().metodo().equals("DELETE") && f.ultima().caminho().equals("/v3/payments/pay_1"));
        f.resp = q -> "{}"; s.devolver("pay_1", "D1", new BigDecimal("12.50"));
        confere("Asaas devolver: POST refund com valor", f.ultima().caminho().equals("/v3/payments/pay_1/refund") && f.ultima().json().path("value").decimalValue().compareTo(new BigDecimal("12.5")) == 0);

        var n = s.interpretarWebhook(M.readTree("{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_1\",\"externalReference\":\"tx1\",\"value\":12.5,\"status\":\"RECEIVED\"}}"));
        confere("Asaas webhook: PAYMENT_RECEIVED", n.size() == 1 && n.get(0).txid().equals("tx1") && n.get(0).endToEndId().equals("pay_1") && n.get(0).valor().compareTo(new BigDecimal("12.5")) == 0);
        confere("Asaas webhook: outros eventos ignorados", s.interpretarWebhook(M.readTree("{\"event\":\"PAYMENT_OVERDUE\",\"payment\":{\"id\":\"pay_1\",\"externalReference\":\"tx1\"}}")).isEmpty());
        confere("Asaas webhook: sem externalReference ignorado", s.interpretarWebhook(M.readTree("{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_9\"}}")).isEmpty());

        confere("Asaas registrarWebhook: exige e-mail", erro(IllegalStateException.class, () -> { s.registrarWebhook("https://x"); return null; }));
        v.put("asaas.emailWebhook", "loja@x.com"); f.limpa(); s.registrarWebhook("https://x/hook?token=S");
        confere("Asaas registrarWebhook: POST /v3/webhooks", f.ultima().caminho().equals("/v3/webhooks") && f.ultima().json().path("url").asText().equals("https://x/hook?token=S") && f.ultima().json().path("events").size() == 2);
        f.srv.stop(0);
    }

    // ---------------------------------------------------------------- Sicoob (padrão Bacen, credenciais no corpo + client_id)
    static void sicoob() throws Exception {
        Falso f = new Falso();
        Map<String, Object> v = new HashMap<>(Map.of("sicoob.baseUrl", f.url() + "/pix/api/v2", "sicoob.tokenUrl", f.url() + "/token", "sicoob.clientId", "CID", "sicoob.clientSecret", "SEC", "chave", "chave@x", "sicoob.cabecalhos", "x-extra=1;x-outro=a=b"));
        SicoobPixService s = monta(new SicoobPixService(), v);
        confere("Sicoob: provider", s.provider() == PixProvider.SICOOB);
        confere("Sicoob: falta só o certificado", s.problemasDeConfiguracao().size() == 1 && s.problemasDeConfiguracao().get(0).contains("PIX_SICOOB_CERTIFICADO"));

        f.resp = r -> r.caminho().equals("/token") ? "{\"access_token\":\"AT\",\"expires_in\":300}" : "{\"pixCopiaECola\":\"00020126SC\",\"loc\":{\"location\":\"loc/1\"}}";
        var c = s.criar("tx1", new BigDecimal("12.5"), 300, "Venda 1");
        Req t = f.reqs.get(0), cob = f.reqs.get(1);
        confere("Sicoob token: POST no tokenUrl, formulário", t.metodo().equals("POST") && t.caminho().equals("/token") && "application/x-www-form-urlencoded".equals(t.cab("Content-Type")));
        confere("Sicoob token: credenciais no corpo", t.corpo().contains("grant_type=client_credentials") && t.corpo().contains("client_id=CID") && t.corpo().contains("client_secret=SEC"));
        confere("Sicoob token: escopos do banco", t.corpo().contains("payloadlocation.write") && t.corpo().contains("cobv.read"));
        confere("Sicoob token: cabeçalho client_id e extras", "CID".equals(t.cab("client_id")) && "1".equals(t.cab("x-extra")) && "a=b".equals(t.cab("x-outro")));
        confere("Sicoob cob: PUT /cob/{txid} com Bearer e client_id", cob.metodo().equals("PUT") && cob.caminho().equals("/pix/api/v2/cob/tx1") && "Bearer AT".equals(cob.cab("Authorization")) && "CID".equals(cob.cab("client_id")) && "1".equals(cob.cab("x-extra")));
        JsonNode j = cob.json();
        confere("Sicoob cob: corpo Bacen", j.path("valor").path("original").asText().equals("12.50") && j.path("calendario").path("expiracao").asInt() == 300 && j.path("chave").asText().equals("chave@x"));
        confere("Sicoob cob: copia e cola", c.pixCopiaECola().equals("00020126SC"));
        f.resp = r -> "{\"status\":\"ATIVA\"}"; s.consultar("tx1");
        confere("Sicoob: token em cache (1 só pedido)", f.reqs.stream().filter(q -> q.caminho().equals("/token")).count() == 1);

        f.resp = r -> "{\"status\":\"CONCLUIDA\",\"pix\":[{\"endToEndId\":\"E1\",\"valor\":\"12.50\",\"horario\":\"2026-10-14T03:10:00Z\"}]}";
        var st = s.consultar("tx1");
        confere("Sicoob consultar: CONCLUIDA com e2e e valor", st.status() == PixStatus.CONCLUIDA && "E1".equals(st.endToEndId()) && st.valor().compareTo(new BigDecimal("12.50")) == 0);
        f.resp = r -> "{\"status\":\"REMOVIDA_PELO_PSP\"}";
        confere("Sicoob consultar: REMOVIDA_PELO_PSP", s.consultar("tx1").status() == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR);
        f.resp = r -> "{}"; s.cancelar("tx1");
        confere("Sicoob cancelar: PATCH com status", f.ultima().metodo().equals("PATCH") && f.ultima().json().path("status").asText().equals("REMOVIDA_PELO_USUARIO_RECEBEDOR"));
        s.devolver("E1", "D1", new BigDecimal("12.5"));
        confere("Sicoob devolver: PUT /pix/{e2e}/devolucao/{id}", f.ultima().metodo().equals("PUT") && f.ultima().caminho().equals("/pix/api/v2/pix/E1/devolucao/D1") && f.ultima().json().path("valor").asText().equals("12.50"));
        var n = s.interpretarWebhook(M.readTree("{\"pix\":[{\"endToEndId\":\"E1\",\"txid\":\"tx1\",\"valor\":\"12.50\",\"horario\":\"2026-10-14T03:10:00Z\"},{\"txid\":\"semE2e\"}]}"));
        confere("Sicoob webhook: formato Bacen (ignora item sem e2e)", n.size() == 1 && n.get(0).txid().equals("tx1") && n.get(0).endToEndId().equals("E1"));
        // prefixo da API: Sicredi usa /api/v2; PREFIXO_API="-" remove; valor explícito vence o padrão do banco
        Falso g = new Falso(); g.resp = r -> r.caminho().equals("/oauth/token") ? "{\"access_token\":\"AT\"}" : "{\"pixCopiaECola\":\"X\"}";
        Map<String, Object> w = new HashMap<>(Map.of("sicredi.baseUrl", g.url(), "sicredi.clientId", "a", "sicredi.clientSecret", "b", "chave", "k"));
        SicrediPixService sr = monta(new SicrediPixService(), w); sr.criar("t1", BigDecimal.ONE, 60, null);
        confere("Sicredi: prefixo padrão /api/v2 e token em {base}/oauth/token", g.ultima().caminho().equals("/api/v2/cob/t1") && g.reqs.get(0).caminho().equals("/oauth/token"));
        confere("Sicredi: Basic no cabeçalho e corpo de formulário (basic-form)", g.reqs.get(0).cab("Authorization").startsWith("Basic ") && g.reqs.get(0).corpo().startsWith("grant_type=client_credentials"));
        w.put("sicredi.prefixoApi", "-"); sr.criar("t2", BigDecimal.ONE, 60, null);
        confere("Sicredi: PREFIXO_API=- remove o prefixo", g.ultima().caminho().equals("/cob/t2"));
        w.put("sicredi.prefixoApi", "/v9"); sr.criar("t3", BigDecimal.ONE, 60, null);
        confere("Sicredi: PREFIXO_API explícito vence", g.ultima().caminho().equals("/v9/cob/t3"));
        g.srv.stop(0);
        f.srv.stop(0);
    }

    // ---------------------------------------------------------------- Efí (basic-json) e caminho cob-emv
    static void efi() throws Exception {
        Falso f = new Falso();
        Map<String, Object> v = new HashMap<>(Map.of("efi.baseUrl", f.url(), "efi.clientId", "CID", "efi.clientSecret", "SEC", "efi.chave", "chave-efi", "efi.caminhoCob", "/cob-emv"));
        EfiPixService s = monta(new EfiPixService(), v);
        f.resp = r -> r.caminho().equals("/oauth/token") ? "{\"access_token\":\"AT\",\"expires_in\":600}" : "{\"emv\":\"00020126EF\"}";
        var c = s.criar("tx1", new BigDecimal("5"), 120, null);
        Req t = f.reqs.get(0), cob = f.reqs.get(1);
        confere("Efí token: {base}/oauth/token, Basic + JSON", t.caminho().equals("/oauth/token") && ("Basic " + Base64.getEncoder().encodeToString("CID:SEC".getBytes())).equals(t.cab("Authorization")) && t.json().path("grant_type").asText().equals("client_credentials"));
        confere("Efí: prefixo /v2, chave própria e recurso cob-emv", cob.caminho().equals("/v2/cob-emv/tx1") && cob.json().path("chave").asText().equals("chave-efi"));
        confere("Efí: copia e cola vindo de \"emv\"", c.pixCopiaECola().equals("00020126EF"));
        f.resp = r -> "{}"; s.registrarWebhook("https://x/h");
        confere("Efí webhook: PUT /v2/webhook/{chave} sem skip-mtls", f.ultima().caminho().equals("/v2/webhook/chave-efi") && f.ultima().cab("x-skip-mtls-checking") == null);
        v.put("efi.skipMtlsWebhook", true); s.registrarWebhook("https://x/h");
        confere("Efí webhook: skip-mtls quando configurado", "true".equals(f.ultima().cab("x-skip-mtls-checking")));
        confere("Efí: URL e formato padrão do provedor", PixProvider.EFI.urlPadrao() != null && monta(new EfiPixService(), Map.of("efi.clientId", "a")).problemasDeConfiguracao().stream().noneMatch(p -> p.contains("_URL")));
        f.srv.stop(0);
    }
}
