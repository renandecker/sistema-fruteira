package br.com.fruteira.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Confere o NCM em duas fontes públicas: BrasilAPI (consulta por código, rápida) e a tabela OFICIAL do Portal Único Siscomex
 * (JSON completo, baixado no máximo 1x por dia). Uma confirma a outra. Status: VALIDO | INVALIDO | VENCIDO | AUSENTE | NAO_VERIFICADO.
 * Para a NFC-e, o NCM tem de ter 8 dígitos, existir na tabela e estar vigente.
 */
@ApplicationScoped
public class NcmService {
    private static final Logger LOG = Logger.getLogger(NcmService.class);
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Sugestao(String ncm, String descricao) {}
    public record Resultado(String ncm, String status, String descricao, LocalDate inicio, LocalDate fim, String fonte, String mensagem, List<Sugestao> sugestoes) {}
    private record Item(String descricao, LocalDate inicio, LocalDate fim) {}
    private record Guardado(Resultado r, Instant em) {}

    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "fruteira.ncm.brasilapi-url", defaultValue = "https://brasilapi.com.br/api/ncm/v1") String brasilApi;
    @ConfigProperty(name = "fruteira.ncm.siscomex-url", defaultValue = "https://portalunico.siscomex.gov.br/classif/api/publico/nomenclatura/download/json?perfil=PUBLICO") String siscomexUrl;
    @ConfigProperty(name = "fruteira.ncm.cache-horas", defaultValue = "24") int cacheHoras;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ConcurrentMap<String, Guardado> cache = new ConcurrentHashMap<>();
    private volatile Map<String, Item> tabela; private volatile LocalDate tabelaDia; private volatile Instant falhaTabela = Instant.EPOCH;

    public static String normalizar(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** preferirTabela=true (rotina em lote): usa a tabela oficial primeiro (1 download); false (cadastro): BrasilAPI primeiro (resposta rápida). */
    public Resultado verificar(String bruto, boolean preferirTabela) {
        String n = normalizar(bruto);
        if (n.isEmpty()) return res(n, "AUSENTE", null, null, null, null, "NCM não informado", List.of());
        if (n.length() != 8) return res(n, "INVALIDO", null, null, null, null, "O NCM deve ter exatamente 8 dígitos (informado: " + n.length() + ")", List.of());
        Guardado g = cache.get(n);
        if (g != null && g.em().isAfter(Instant.now().minus(Duration.ofHours(cacheHoras)))) return g.r();
        Resultado r = consultar(n, preferirTabela);
        if (!"NAO_VERIFICADO".equals(r.status())) cache.put(n, new Guardado(r, Instant.now()));
        return r;
    }

    private Resultado consultar(String n, boolean preferirTabela) {
        List<String> ordem = preferirTabela ? List.of("siscomex", "brasilapi") : List.of("brasilapi", "siscomex");
        List<String> falhas = new ArrayList<>(); String naoConstaEm = null;
        for (String fonte : ordem) {
            try {
                Item it;
                if (fonte.equals("siscomex")) { Map<String, Item> t = tabela(); it = t.get(n); }
                else it = consultarBrasilApi(n);
                if (it != null) return classificar(n, it, fonte);          // achou: a primeira fonte que confirma decide
                if (naoConstaEm == null) naoConstaEm = fonte;
            } catch (Exception e) { falhas.add(fonte + ": " + e.getMessage()); }
        }
        if (naoConstaEm != null) return res(n, "INVALIDO", null, null, null, naoConstaEm, "NCM não consta na tabela oficial vigente (" + naoConstaEm + ")", sugestoes(n));
        LOG.warnf("NCM %s não verificado: %s", n, falhas);
        return res(n, "NAO_VERIFICADO", null, null, null, null, "Não foi possível consultar as fontes oficiais agora (" + String.join("; ", falhas) + ")", List.of());
    }

    private Resultado classificar(String n, Item it, String fonte) {
        LocalDate hoje = LocalDate.now();
        if (it.fim() != null && it.fim().isBefore(hoje))
            return res(n, "VENCIDO", it.descricao(), it.inicio(), it.fim(), fonte, "NCM com vigência encerrada em " + it.fim().format(BR), sugestoes(n));
        if (it.inicio() != null && it.inicio().isAfter(hoje))
            return res(n, "INVALIDO", it.descricao(), it.inicio(), it.fim(), fonte, "NCM ainda não vigente (início em " + it.inicio().format(BR) + ")", sugestoes(n));
        return res(n, "VALIDO", it.descricao(), it.inicio(), it.fim(), fonte, "NCM válido e vigente", List.of());
    }
    private static Resultado res(String n, String st, String d, LocalDate i, LocalDate f, String fonte, String msg, List<Sugestao> s) { return new Resultado(n, st, d, i, f, fonte, msg, s); }

    /** GET {brasilapi}/{codigo} — aceita as chaves em português (codigo, descricao, data_inicio, data_fim) e em inglês (code, description, start_date, end_date). */
    private Item consultarBrasilApi(String n) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(brasilApi.replaceAll("/+$", "") + "/" + n)).timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("BrasilAPI respondeu HTTP " + r.statusCode());
        JsonNode j = mapper.readTree(r.body());
        if (j.isArray()) {                                                 // alguns endpoints devolvem lista: procura o código exato
            JsonNode achado = null;
            for (JsonNode x : j) if (n.equals(normalizar(txt(x, "codigo", "code")))) { achado = x; break; }
            if (achado == null) return null;
            j = achado;
        }
        if (txt(j, "codigo", "code").isEmpty()) return null;
        return new Item(txt(j, "descricao", "description"), data(txt(j, "data_inicio", "start_date")), data(txt(j, "data_fim", "end_date")));
    }

    /** Tabela oficial do Siscomex (todas as NCMs de 8 dígitos). Baixa 1x por dia; se falhar, usa a última e espera 10 min para tentar de novo. */
    private synchronized Map<String, Item> tabela() throws Exception {
        LocalDate hoje = LocalDate.now();
        if (tabela != null && hoje.equals(tabelaDia)) return tabela;
        if (Instant.now().isBefore(falhaTabela.plus(Duration.ofMinutes(10)))) { if (tabela != null) return tabela; throw new IllegalStateException("tabela oficial indisponível (nova tentativa em instantes)"); }
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(siscomexUrl)).timeout(Duration.ofSeconds(90)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("Siscomex respondeu HTTP " + r.statusCode());
            JsonNode raiz; try (InputStream in = r.body()) { raiz = mapper.readTree(in); }
            JsonNode lista = raiz.isArray() ? raiz : raiz.path("Nomenclaturas");
            if (!lista.isArray()) for (JsonNode f : raiz) if (f.isArray()) { lista = f; break; }     // tolera troca do nome do campo
            Map<String, Item> m = new HashMap<>();
            for (JsonNode x : lista) {
                String cod = normalizar(txt(x, "Codigo", "codigo", "Código"));
                if (cod.length() == 8) m.put(cod, new Item(txt(x, "Descricao", "descricao", "Descrição"), data(txt(x, "Data_Inicio", "data_inicio", "DataInicio")), data(txt(x, "Data_Fim", "data_fim", "DataFim"))));
            }
            if (m.isEmpty()) throw new IllegalStateException("a tabela do Siscomex veio sem NCMs de 8 dígitos");
            tabela = m; tabelaDia = hoje; LOG.infof("Tabela oficial de NCM carregada: %d códigos", m.size());
            return m;
        } catch (Exception e) {
            falhaTabela = Instant.now();
            if (tabela != null) { LOG.warn("Falha ao atualizar a tabela de NCM; usando a última baixada: " + e.getMessage()); return tabela; }
            throw e;
        }
    }

    /** Códigos vigentes da mesma subposição (6 dígitos; senão 4) — só se a tabela oficial já estiver carregada. */
    private List<Sugestao> sugestoes(String n) {
        Map<String, Item> t = tabela;
        if (t == null || n.length() != 8) return List.of();
        LocalDate hoje = LocalDate.now();
        for (int tam : new int[]{6, 4}) {
            String p = n.substring(0, tam);
            List<Sugestao> l = t.entrySet().stream().filter(e -> e.getKey().startsWith(p) && !e.getKey().equals(n) && vigente(e.getValue(), hoje))
                .sorted(Map.Entry.comparingByKey()).limit(6).map(e -> new Sugestao(e.getKey(), e.getValue().descricao())).toList();
            if (!l.isEmpty()) return l;
        }
        return List.of();
    }
    private static boolean vigente(Item it, LocalDate hoje) { return (it.inicio() == null || !it.inicio().isAfter(hoje)) && (it.fim() == null || !it.fim().isBefore(hoje)); }

    private static String txt(JsonNode no, String... chaves) {
        for (String c : chaves) { JsonNode v = no.get(c); if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText().trim(); }
        return "";
    }
    private static LocalDate data(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); } catch (Exception e) { /* tenta dd/MM/aaaa */ }
        try { return LocalDate.parse(s, BR); } catch (Exception e) { return null; }
    }
}
