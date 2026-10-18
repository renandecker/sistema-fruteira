package br.com.fruteira.vendas;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Quem chega pelo túnel do Ngrok só enxerga o webhook do Pix. O Ngrok abre a porta do vendas-service para a internet; sem este filtro
 * qualquer um com o endereço do túnel alcançaria o resto da API (em desenvolvimento a identidade vem de cabeçalhos, sem Keycloak).
 * Reconhece o túnel pelo host da requisição (ou X-Forwarded-Host): domínios do Ngrok, NGROK_URL ou o túnel detectado.
 * Pelo nginx/Keycloak (produção com domínio próprio) nada muda.
 */
@Provider
public class NgrokFilter implements ContainerRequestFilter {
    /** Lazy de propósito: os filtros JAX-RS são criados na inicialização estática, antes de a configuração de execução (@ConfigMapping) existir;
     *  injetar o PixConfig direto derruba a partida com SRCFG00027 "Could not find a mapping". Aqui ele só é resolvido na primeira requisição. */
    @Inject Instance<PixConfig> configLazy;

    @Override
    public void filter(ContainerRequestContext ctx) {
        PixConfig config = configLazy.get();
        String host = ctx.getUriInfo().getRequestUri().getHost();
        String repassado = ctx.getHeaderString("X-Forwarded-Host");
        if (!PixWebhook.ehHostDoTunel(host, config) && !PixWebhook.ehHostDoTunel(primeiro(repassado), config)) return;
        String caminho = "/" + ctx.getUriInfo().getPath().replaceAll("^/+|/+$", "");
        if (!("POST".equals(ctx.getMethod()) && PixWebhook.CAMINHO_PADRAO.equals(caminho)))
            ctx.abortWith(Response.status(404).build());
    }

    private static String primeiro(String lista) { return lista == null ? null : lista.split(",")[0].trim(); }
}
