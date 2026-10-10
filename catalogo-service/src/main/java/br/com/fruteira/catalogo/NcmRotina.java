package br.com.fruteira.catalogo;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Verifica o NCM de TODOS os produtos nas fontes oficiais, uma vez por dia (agendador) ou quando o usuário pede (botão).
 * Atualiza em cada produto: status, descrição, fonte, fim da vigência e a data da conferência; e normaliza o código (só 8 dígitos).
 * NÃO troca um NCM por outro sozinho (decisão fiscal): marca INVALIDO/VENCIDO e mostra sugestões da mesma subposição.
 */
@ApplicationScoped
public class NcmRotina {
    private static final Logger LOG = Logger.getLogger(NcmRotina.class);
    @Inject NcmService ncm;
    @ConfigProperty(name = "fruteira.ncm.agendado", defaultValue = "true") boolean agendado;
    @ConfigProperty(name = "fruteira.ncm.verificar-na-subida", defaultValue = "true") boolean naSubida;
    private final AtomicBoolean rodando = new AtomicBoolean(false);
    private final ExecutorService fundo = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ncm-rotina"); t.setDaemon(true); return t; });

    public boolean rodando() { return rodando.get(); }

    /** Todo dia (padrão 03:00; NCM_CRON no .env). */
    @Scheduled(cron = "{fruteira.ncm.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void agendada() { if (agendado) executar("AGENDADA"); }

    /** Ao subir, confere em segundo plano se existir produto ainda sem verificação. */
    void aoSubir(@Observes @Priority(40) StartupEvent ev) {
        if (!naSubida) return;
        fundo.submit(() -> {
            try { long pend = QuarkusTransaction.requiringNew().call(() -> Produto.count("ativo = true and ncmVerificadoEm is null")); if (pend > 0) executar("INICIO"); }
            catch (Exception e) { LOG.warn("Verificação de NCM na subida não executada: " + e.getMessage()); }
        });
    }

    /** Botão "executar agora": roda em segundo plano (pode levar alguns segundos); false se já houver uma execução. */
    public boolean iniciar(String origem) {
        if (rodando.get()) return false;
        fundo.submit(() -> executar(origem));
        return true;
    }

    public NcmExecucao executar(String origem) {
        if (!rodando.compareAndSet(false, true)) return null;
        NcmExecucao ex = new NcmExecucao(); ex.origem = origem; ex.inicio = LocalDateTime.now();
        try {
            // 1) lê os códigos (transação curta)   2) consulta as APIs SEM transação aberta   3) grava (transação curta)
            Set<String> codigos = QuarkusTransaction.requiringNew().call(() -> {
                Set<String> s = new LinkedHashSet<>();
                for (Produto p : Produto.<Produto>list("ativo", true)) { String n = NcmService.normalizar(p.ncm); if (!n.isEmpty()) s.add(n); }
                return s;
            });
            Map<String, NcmService.Resultado> resultados = new HashMap<>();
            for (String c : codigos) resultados.put(c, ncm.verificar(c, true));        // 1ª consulta baixa a tabela oficial 1x
            QuarkusTransaction.requiringNew().run(() -> {
                for (Produto p : Produto.<Produto>list("ativo", true)) {
                    ex.total++;
                    String n = NcmService.normalizar(p.ncm);
                    NcmService.Resultado r = n.isEmpty() ? ncm.verificar("", true) : resultados.get(n);
                    if (n.length() == 8 && !n.equals(p.ncm)) { p.ncm = n; ex.normalizados++; }
                    boolean semAnswer = r == null || "NAO_VERIFICADO".equals(r.status());
                    boolean tinhaBom = p.ncmStatus != null && !"NAO_VERIFICADO".equals(p.ncmStatus);
                    if (semAnswer && tinhaBom) { ex.naoVerificados++; continue; }       // API fora do ar: não apaga o que já estava confirmado
                    String st = r == null ? "NAO_VERIFICADO" : r.status();
                    p.ncmStatus = st; p.ncmVerificadoEm = LocalDateTime.now();
                    if (r != null) { p.ncmDescricao = r.descricao() == null ? null : r.descricao().substring(0, Math.min(500, r.descricao().length())); p.ncmFonte = r.fonte(); p.ncmFim = r.fim(); }
                    switch (st) { case "VALIDO" -> ex.validos++; case "INVALIDO" -> ex.invalidos++; case "VENCIDO" -> ex.vencidos++; case "AUSENTE" -> ex.ausentes++; default -> ex.naoVerificados++; }
                }
            });
        } catch (Exception e) { LOG.error("Falha na verificação de NCM", e); ex.erro = String.valueOf(e.getMessage()); }
        finally {
            ex.fim = LocalDateTime.now();
            try { QuarkusTransaction.requiringNew().run(ex::persist); } catch (Exception e) { LOG.warn("Não foi possível gravar o resumo da verificação: " + e.getMessage()); }
            rodando.set(false);
        }
        LOG.infof("Verificação de NCM (%s): %d produtos — %d válidos, %d inválidos, %d vencidos, %d sem NCM, %d não verificados, %d normalizados",
            origem, ex.total, ex.validos, ex.invalidos, ex.vencidos, ex.ausentes, ex.naoVerificados, ex.normalizados);
        return ex;
    }
}
