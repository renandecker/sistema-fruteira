package br.com.fruteira.vendas;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Mostra no log a URL pública do webhook ao subir e, com PIX_WEBHOOK_AUTO_REGISTRAR=true, registra-a no provedor (o endereço do Ngrok
 * muda a cada início no plano grátis). Em segundo plano, com tentativas: o Ngrok pode demorar alguns segundos. Nunca derruba o serviço.
 * Padrão desligado: registrar SOBRESCREVE o webhook da chave/conta no provedor (não use com a conta de produção da loja).
 */
@ApplicationScoped
public class PixWebhookAuto {
    private static final Logger LOG = Logger.getLogger(PixWebhookAuto.class);
    @Inject PixConfig config;
    @Inject PixService pix;

    void aoIniciar(@Observes StartupEvent ev) {
        Thread t = new Thread(this::executar, "pix-webhook-auto"); t.setDaemon(true); t.start();
    }

    private void executar() {
        try {
            if (PixProvider.deTexto(config.provider()) == PixProvider.SIMULADOR) return;
            for (int i = 0; i < 12; i++) {   // até ~1 min esperando o túnel
                PixWebhook.esquecer();
                var url = PixWebhook.resolver(config);
                if (url.isPresent()) {
                    LOG.infof("Webhook Pix: %s (origem: %s)", url.get().mascarada(), url.get().origem());
                    if (!config.webhook().autoRegistrar()) return;
                    try { pix.servico().registrarWebhook(url.get().url()); LOG.infof("Webhook Pix registrado em %s", pix.provedor().rotulo()); }
                    catch (UnsupportedOperationException e) { LOG.info("Este provedor recebe a URL em cada cobrança (nada a registrar)."); }
                    catch (Exception e) { LOG.warnf("Não consegui registrar o webhook no provedor: %s", PixWebhook.mascarar(String.valueOf(e.getMessage()))); }
                    return;
                }
                if (config.webhook().ngrokApi().filter(s -> !s.isBlank() && !s.equals("-")).isEmpty()) return;   // sem túnel e sem como esperar por um
                Thread.sleep(5000);
            }
            LOG.info("Webhook Pix: sem URL pública (nem FRUTEIRA_PIX_WEBHOOK_URL, nem túnel do Ngrok ativo).");
        } catch (Exception e) { LOG.debugf("Webhook Pix (auto): %s", e.getMessage()); }
    }
}
