package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Mantém o estado das transações (consultado pelo front) e fala com o provedor. O estado é gravado em disco para sobreviver a reinício. */
@ApplicationScoped
public class TefAgente {
    private static final Logger LOG = Logger.getLogger(TefAgente.class);
    private static final Set<String> EM_ANDAMENTO = Set.of("AGUARDANDO_CARTAO", "AGUARDANDO_SENHA", "PROCESSANDO");

    public static class Estado {
        public String requisicao, estado, mensagem, atualizadoEm;
        public Pedido pedido; public Resultado resultado;
        public boolean confirmada, desfeita;
        @JsonIgnore public volatile boolean cancelar;
    }

    @Inject @Any Instance<TefProvider> providers;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "tef.provider", defaultValue = "simulador") String providerNome;
    @ConfigProperty(name = "tef.dados", defaultValue = "./data/tef-agente.json") String arquivo;
    private final ConcurrentMap<String, Estado> estados = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public TefProvider provider() {
        for (TefProvider p : providers) if (p.nome().equalsIgnoreCase(providerNome)) return p;
        throw new IllegalStateException("Provedor TEF desconhecido: " + providerNome);
    }

    @PostConstruct
    void carregar() {
        try {
            File f = new File(arquivo);
            if (!f.exists()) return;
            for (Estado e : mapper.readValue(f, new TypeReference<List<Estado>>() {})) {
                if (EM_ANDAMENTO.contains(e.estado)) { e.estado = "ERRO"; e.mensagem = "Agente reiniciado durante a operação (verifique o pinpad e a reconciliação)"; }
                estados.put(e.requisicao, e);
            }
        } catch (Exception ex) { LOG.warn("Não foi possível ler o estado do agente: " + ex.getMessage()); }
    }
    private synchronized void salvar() {
        try {
            if (estados.size() > 500) estados.values().stream().filter(e -> !EM_ANDAMENTO.contains(e.estado) && (e.confirmada || e.desfeita || !"APROVADA".equals(e.estado)))
                .sorted(Comparator.comparing(e -> e.atualizadoEm)).limit(estados.size() - 400).forEach(e -> estados.remove(e.requisicao));
            Path p = Path.of(arquivo).toAbsolutePath(); Files.createDirectories(p.getParent());
            mapper.writeValue(p.toFile(), new ArrayList<>(estados.values()));
        } catch (Exception ex) { LOG.warn("Não foi possível gravar o estado do agente: " + ex.getMessage()); }
    }
    private void tocar(Estado e, String estado, String mensagem) { e.estado = estado; e.mensagem = mensagem; e.atualizadoEm = LocalDateTime.now().toString(); salvar(); }

    /** Inicia (idempotente: repetir a mesma requisição devolve a transação existente). */
    public Estado iniciar(Pedido p) {
        Estado existente = estados.get(p.requisicao());
        if (existente != null) return existente;
        Estado e = new Estado(); e.requisicao = p.requisicao(); e.pedido = p;
        estados.put(e.requisicao, e);
        tocar(e, "AGUARDANDO_CARTAO", "Iniciando…");
        pool.submit(() -> executar(e));
        return e;
    }
    private void executar(Estado e) {
        try {
            Resultado r = provider().executar(e.pedido, pr -> tocar(e, pr.estado(), pr.mensagem()), () -> e.cancelar);
            e.resultado = r; tocar(e, r.status(), r.mensagem());
        } catch (Exception ex) {
            LOG.error("Falha na transação " + e.requisicao, ex);
            tocar(e, "ERRO", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }
    public Optional<Estado> consultar(String req) { return Optional.ofNullable(estados.get(req)); }
    public boolean cancelar(String req) {
        Estado e = estados.get(req);
        if (e == null || !EM_ANDAMENTO.contains(e.estado)) return false;
        e.cancelar = true; return true;
    }
    public Estado confirmar(String req) throws Exception {
        Estado e = obter(req);
        if (e.confirmada) return e;
        if (!"APROVADA".equals(e.estado) || e.desfeita) throw new IllegalStateException("Só é possível confirmar transação aprovada e não desfeita");
        provider().confirmar(req); e.confirmada = true; tocar(e, e.estado, e.mensagem); return e;
    }
    public Estado desfazer(String req) throws Exception {
        Estado e = obter(req);
        if (e.desfeita) return e;
        if (!"APROVADA".equals(e.estado) || e.confirmada) throw new IllegalStateException("Só é possível desfazer transação aprovada e ainda não confirmada");
        provider().desfazer(req); e.desfeita = true; tocar(e, "DESFEITA", "Transação desfeita"); return e;
    }
    /** Aprovadas que ainda não foram confirmadas nem desfeitas (precisam de reconciliação com a venda). */
    public List<Estado> pendencias() {
        List<Estado> l = new ArrayList<>();
        for (Estado e : estados.values()) if ("APROVADA".equals(e.estado) && !e.confirmada && !e.desfeita) l.add(e);
        return l;
    }
    public Resultado estornar(Estorno es) throws Exception { return provider().estornar(es); }
    private Estado obter(String req) { Estado e = estados.get(req); if (e == null) throw new NoSuchElementException("Transação desconhecida: " + req); return e; }
}
