package br.com.fruteira.vendas;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/** Resultado da rotina de saúde do Pix e do TEF (ver {@link SaudePagamentos}). */
@Path("/saude/pagamentos") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class SaudeResource {
    @Inject SaudePagamentos saude;

    /** Última verificação, a periodicidade configurada e o histórico recente (em memória; some ao reiniciar o serviço). */
    @GET
    public Map<String, Object> estado() {
        List<SaudePagamentos.Verificacao> h = saude.historico();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("intervalo", saude.intervalo());
        m.put("ultima", h.isEmpty() ? null : h.get(0));
        m.put("historico", h);
        return m;
    }

    /** "Verificar agora" (supervisor). */
    @POST @Path("/verificar")
    public SaudePagamentos.Verificacao verificar() { return saude.executar("MANUAL"); }
}
