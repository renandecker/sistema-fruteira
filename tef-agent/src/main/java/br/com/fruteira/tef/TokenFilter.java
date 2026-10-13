package br.com.fruteira.tef;

import java.util.Optional;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Qualquer página aberta no navegador consegue chamar o localhost: exige o token configurado (X-Tef-Token) quando TEF_TOKEN está definido. */
@Provider @Priority(Priorities.AUTHENTICATION)
public class TokenFilter implements ContainerRequestFilter {
    @ConfigProperty(name = "tef.agente.token") Optional<String> tokenCfg;   // vazio = não configurado
    private String token() { return tokenCfg.orElse(""); }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (token() == null || token().isBlank() || "OPTIONS".equals(ctx.getMethod())) return;
        if (!token().equals(ctx.getHeaderString("X-Tef-Token")))
            ctx.abortWith(Response.status(401).entity("Token do agente TEF inválido").type(MediaType.TEXT_PLAIN).build());
    }
}
