package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

/** API local usada pelo front do caixa (http://127.0.0.1:8090/tef/...). */
@Path("/tef") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class TefResource {
    private static final Set<String> TIPOS = Set.of("CREDITO_A_VISTA", "CREDITO_PARCELADO", "DEBITO");
    @Inject TefAgente agente;

    @GET @Path("/status")
    public Map<String, Object> status() {
        TefProvider p = agente.provider();
        return Map.of("provedor", p.nome(), "pinpad", p.pinpadConectado(), "pendencias", agente.pendencias().size());
    }

    @POST @Path("/transacoes")
    public TefAgente.Estado iniciar(Pedido p) {
        if (p == null || p.requisicao() == null || p.requisicao().isBlank()) throw Http.erro(422, "Informe a requisição");
        if (p.valor() == null || p.valor().signum() <= 0) throw Http.erro(422, "Informe um valor maior que zero");
        if (!TIPOS.contains(p.tipo())) throw Http.erro(422, "Tipo inválido: " + p.tipo());
        return agente.iniciar(p);
    }
    @GET @Path("/transacoes/{req}")
    public TefAgente.Estado consultar(@PathParam("req") String req) {
        return agente.consultar(req).orElseThrow(() -> Http.erro(404, "Transação desconhecida: " + req));
    }
    @POST @Path("/transacoes/{req}/cancelar")
    public Map<String, Object> cancelar(@PathParam("req") String req) { return Map.of("cancelando", agente.cancelar(req)); }

    @POST @Path("/transacoes/{req}/confirmar")
    public TefAgente.Estado confirmar(@PathParam("req") String req) { try { return agente.confirmar(req); } catch (Exception e) { throw Http.erro(409, e.getMessage()); } }

    @POST @Path("/transacoes/{req}/desfazer")
    public TefAgente.Estado desfazer(@PathParam("req") String req) { try { return agente.desfazer(req); } catch (Exception e) { throw Http.erro(409, e.getMessage()); } }

    @GET @Path("/pendencias")
    public List<TefAgente.Estado> pendencias() { return agente.pendencias(); }

    @POST @Path("/estornos")
    public Resultado estornar(Estorno e) {
        if (e == null || e.nsu() == null || e.valor() == null) throw Http.erro(422, "Informe NSU e valor");
        try { return agente.estornar(e); } catch (Exception ex) { throw Http.erro(502, ex.getMessage()); }
    }
}
