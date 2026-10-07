package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * PSP simulado para desenvolvimento e ensaio. NÃO movimenta dinheiro. Gera um "Pix Copia e Cola" com formato BR Code válido
 * (a URL da cobrança é fictícia, então um app bancário real recusa) e depois de PIX_SIM_PAGAR_EM_SEG segundos "paga":
 * o simulador chama o webhook do próprio serviço com o payload padrão do Bacen — o mesmo caminho do PSP real.
 * Os CENTAVOS do valor escolhem o comportamento: ,01 nunca paga (para ver a expiração) · ,03 envia o webhook duplicado (idempotência) · demais: paga.
 */
@ApplicationScoped
public class SimuladorPixProvider implements PixProvider {
    private static final Logger LOG = Logger.getLogger(SimuladorPixProvider.class);
    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    @ConfigProperty(name = "pix.nome", defaultValue = "FRUTEIRA CONVENTOS") String nome;
    @ConfigProperty(name = "pix.cidade", defaultValue = "CIDADE") String cidade;
    @ConfigProperty(name = "pix.simulador.pagar-em-seg", defaultValue = "6") int pagarEm;
    @ConfigProperty(name = "pix.webhook.segredo", defaultValue = "") String segredo;
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8083") int porta;

    private static final class Cob { BigDecimal valor; volatile String status = "ATIVA"; volatile String e2e; volatile LocalDateTime horario; }
    private final Map<String, Cob> cobs = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService agenda = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "pix-simulador"); t.setDaemon(true); return t; });

    @Override public String nome() { return "simulador"; }

    @Override
    public Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) {
        String location = "pix.simulador.local/qr/v2/" + txid;
        Cob c = new Cob(); c.valor = valor; cobs.put(txid, c);
        int centavos = (int) (valor.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue() % 100);
        if (pagarEm > 0 && centavos != 1) agenda.schedule(() -> pagar(txid, centavos == 3 ? 2 : 1), pagarEm, TimeUnit.SECONDS);
        return new Criada(BrCode.dinamico(location, valor, nome, cidade), location);
    }

    @Override
    public Situacao consultar(String txid) {
        Cob c = cobs.get(txid);
        if (c == null) return new Situacao("ATIVA", null, null, null);        // desconhecida (ex.: reinício): mantém
        return new Situacao(c.status, c.e2e, c.valor, c.horario);
    }
    @Override public void cancelar(String txid) { Cob c = cobs.get(txid); if (c != null && "ATIVA".equals(c.status)) c.status = "REMOVIDA"; }
    @Override public void devolver(String endToEndId, String idDevolucao, BigDecimal valor) { LOG.infof("[simulador] devolução %s de R$ %s (%s)", idDevolucao, valor, endToEndId); }
    @Override public void registrarWebhook(String url) { LOG.infof("[simulador] webhook 'registrado': %s", url); }

    /** Simula o cliente pagando: o "PSP" notifica o webhook (vezes > 1 = notificação duplicada). */
    public void pagar(String txid, int vezes) {
        Cob c = cobs.get(txid);
        if (c == null || !"ATIVA".equals(c.status)) return;
        c.status = "CONCLUIDA"; c.e2e = e2e(); c.horario = LocalDateTime.now();
        String json = "{\"pix\":[{\"endToEndId\":\"" + c.e2e + "\",\"txid\":\"" + txid + "\",\"valor\":\""
            + c.valor.setScale(2, RoundingMode.HALF_UP).toPlainString() + "\",\"horario\":\"" + OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"}]}";
        for (int i = 0; i < Math.max(vezes, 1); i++) enviarWebhook(json);
    }
    private void enviarWebhook(String json) {
        try {
            String url = "http://127.0.0.1:" + porta + "/pix/webhook" + (segredo.isBlank() ? "" : "?token=" + URLEncoder.encode(segredo, StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) LOG.warnf("[simulador] webhook respondeu HTTP %d: %s", r.statusCode(), r.body());
        } catch (Exception e) { LOG.warn("[simulador] falha ao chamar o webhook: " + e.getMessage()); }
    }
    private static String e2e() {   // E + ISPB(8) + AAAAMMDDHHMM + 11 aleatórios = 32 caracteres
        StringBuilder sb = new StringBuilder("E00000000" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")));
        for (int i = 0; i < 11; i++) sb.append(ALFABETO.charAt(ThreadLocalRandom.current().nextInt(ALFABETO.length())));
        return sb.toString();
    }
}
