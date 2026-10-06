package br.com.fruteira.tef;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Chrome/Edge exigem "Access-Control-Allow-Private-Network: true" no preflight quando uma página (ex.: https://loja...)
 * chama um endereço local (127.0.0.1). Sem isso, o front HTTPS não consegue falar com o agente.
 */
@ApplicationScoped
public class PrivateNetworkFilter {
    void registrar(@Observes Filters filters) {
        filters.register(rc -> {
            if ("true".equalsIgnoreCase(rc.request().getHeader("Access-Control-Request-Private-Network")))
                rc.response().putHeader("Access-Control-Allow-Private-Network", "true");
            rc.next();
        }, 1000);
    }
}
