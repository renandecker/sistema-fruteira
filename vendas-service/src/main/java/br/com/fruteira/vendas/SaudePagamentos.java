package br.com.fruteira.vendas;

import br.com.fruteira.vendas.SaudeAvaliacao.Item;
import br.com.fruteira.vendas.SaudeAvaliacao.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Rotina agendada (padrão: de hora em hora; SAUDE_INTERVALO no .env) que confere se o Pix e o TEF estão funcionando como foi definido:
 * só o provedor Pix de PIX_PROVIDER e só o TEF de TEF_PROVIDER (vazio/"nenhum" = TEF não conferido). Não cria cobrança nem cobra cartão:
 * autentica/consulta o provedor Pix, pergunta o status ao agente TEF e procura pagamentos recebidos sem venda.
 * O resultado fica no log, em GET /saude/pagamentos (tela "Saúde dos pagamentos" e aviso no PDV) e nas últimas SAUDE_HISTORICO verificações.
 */
@ApplicationScoped
public class SaudePagamentos {
    private static final Logger LOG = Logger.getLogger(SaudePagamentos.class);
    public record Verificacao(LocalDateTime quando, String origem, Status geral, List<Item> itens) {}

    @Inject PixService pix;
    @Inject PixConfig config;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "fruteira.saude.agendado", defaultValue = "true") boolean agendado;
    @ConfigProperty(name = "fruteira.saude.intervalo", defaultValue = "1h") String intervalo;
    @ConfigProperty(name = "fruteira.saude.tef-provedor") Optional<String> tefProvedor;
    @ConfigProperty(name = "fruteira.saude.tef-url", defaultValue = "http://127.0.0.1:8090") String tefUrl;
    @ConfigProperty(name = "fruteira.saude.tef-token") Optional<String> tefToken;
    @ConfigProperty(name = "fruteira.saude.historico", defaultValue = "48") int historico;

    private final ConcurrentLinkedDeque<Verificacao> ultimas = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean rodando = new AtomicBoolean(false);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Periodicidade em SAUDE_INTERVALO (1h, 30m, 15m...; "off" desliga o agendador). A 1ª conferência vem SAUDE_PRIMEIRA_EM (1m) depois da partida. */
    @Scheduled(every = "{fruteira.saude.intervalo}", delayed = "{fruteira.saude.primeira-em}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "saude-pagamentos")
    void agendada() { if (agendado) executar("AGENDADA"); }

    public String intervalo() { return agendado ? intervalo : "desligado"; }
    public Optional<Verificacao> ultima() { return Optional.ofNullable(ultimas.peekFirst()); }
    public List<Verificacao> historico() { return new ArrayList<>(ultimas); }

    /** Roda agora (botão ou agendador). Se já houver uma em andamento, devolve a última. */
    public Verificacao executar(String origem) {
        if (!rodando.compareAndSet(false, true)) return ultima().orElse(null);
        try {
            List<Item> itens = new ArrayList<>();
            itens.add(conferirPix());
            itens.add(conferirTef());
            Verificacao v = new Verificacao(LocalDateTime.now(), origem, SaudeAvaliacao.geral(itens), List.copyOf(itens));
            ultimas.addFirst(v);
            while (ultimas.size() > Math.max(1, historico)) ultimas.removeLast();
            for (Item i : itens) {
                String linha = "Saúde dos pagamentos [" + origem + "] " + i.nome() + (i.provedor() != null ? " (" + i.provedor() + ")" : "") + ": " + i.status() + " — " + i.detalhe();
                if (i.status() == Status.FALHA) LOG.error(linha); else if (i.status() == Status.ALERTA) LOG.warn(linha); else LOG.info(linha);
            }
            return v;
        } finally { rodando.set(false); }
    }

    private Item conferirPix() {
        long t0 = System.currentTimeMillis();
        String nome = config.provider();
        try {
            PixProviderService s = pix.servico();            // 500/501 se PIX_PROVIDER for inválido ou sem integração
            nome = s.provider().name();
            boolean simulador = s.provider() == PixProvider.SIMULADOR;
            List<String> problemas = s.problemasDeConfiguracao();
            String erro = null;
            if (problemas.isEmpty() && !simulador) {
                try { s.testarConexao(); } catch (Exception e) { erro = SaudeAvaliacao.limpar(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()); }
            }
            boolean semWebhook = !simulador && PixWebhook.url(config).isEmpty();
            long semVenda = 0;
            try {
                LocalDateTime limite = LocalDateTime.now().minusMinutes(30);
                semVenda = QuarkusTransaction.requiringNew().call(() -> PixCobranca.count("status = 'CONCLUIDA' and vendaId is null and pagoEm < ?1", limite));
            } catch (Exception e) { LOG.warn("Saúde: não consegui contar Pix sem venda: " + e.getMessage()); }
            return SaudeAvaliacao.pix(nome, simulador, problemas, erro, semWebhook, semVenda, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new Item("PIX", nome, Status.FALHA, SaudeAvaliacao.limpar(e.getMessage()), System.currentTimeMillis() - t0);
        }
    }

    private Item conferirTef() {
        long t0 = System.currentTimeMillis();
        String definido = tefProvedor.orElse("");
        if (!SaudeAvaliacao.tefDefinido(definido)) return SaudeAvaliacao.tef(definido, null, null, 0, 0, null, 0);   // só o TEF definido é conferido
        String noAgente = null, erro = null; Boolean pinpad = null; int pendencias = 0; long semVenda = 0;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(tefUrl.replaceAll("/+$", "") + "/tef/status")).timeout(Duration.ofSeconds(5)).GET();
            tefToken.filter(x -> !x.isBlank()).ifPresent(x -> b.header("X-Tef-Token", x));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) erro = "HTTP " + r.statusCode() + (r.statusCode() == 401 ? " (token: confira TEF_TOKEN)" : "");
            else {
                JsonNode j = mapper.readTree(r.body());
                noAgente = j.path("provedor").asText(null);
                pinpad = j.has("pinpad") ? j.path("pinpad").asBoolean() : null;
                pendencias = j.path("pendencias").asInt(0);
            }
        } catch (Exception e) { erro = SaudeAvaliacao.limpar(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + " (o agente está rodando em " + tefUrl + "? Se ele roda no computador do caixa e este serviço em outro, ajuste SAUDE_TEF_URL)"; }
        try {
            LocalDateTime limite = LocalDateTime.now().minusMinutes(30);
            semVenda = QuarkusTransaction.requiringNew().call(() -> TefTransacao.count("status = 'APROVADA' and vendaId is null and atualizadoEm < ?1", limite));
        } catch (Exception e) { LOG.warn("Saúde: não consegui contar cartões sem venda: " + e.getMessage()); }
        return SaudeAvaliacao.tef(definido, noAgente, pinpad, pendencias, semVenda, erro, System.currentTimeMillis() - t0);
    }
}
