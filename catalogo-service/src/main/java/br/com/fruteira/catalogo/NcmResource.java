package br.com.fruteira.catalogo;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;

@Path("/ncm") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class NcmResource {
    @Inject NcmService ncm;
    @Inject NcmRotina rotina;
    @Inject Auditor auditor;

    /** Confere um NCM agora (BrasilAPI → tabela oficial do Siscomex). Usado no cadastro do produto. */
    @GET @Path("/{codigo}")
    public NcmService.Resultado consultar(@PathParam("codigo") String codigo) { return ncm.verificar(codigo, false); }

    /** Botão "verificar agora": dispara a rotina de todos os produtos em segundo plano. */
    @POST @Path("/verificar-todos")
    public Map<String, Object> verificarTodos() {
        if (!auditor.supervisorOuMais()) throw new ForbiddenException("Somente supervisor ou gerente");
        boolean iniciou = rotina.iniciar("MANUAL");
        if (iniciou) auditor.registrar("EDICAO", "NCM dos produtos", null, "Verificação de NCM de todos os produtos iniciada manualmente", null, null);
        return Map.of("iniciou", iniciou, "rodando", true);
    }

    /** Estado da rotina e resumo da última execução (para a tela acompanhar). */
    @GET @Path("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("rodando", rotina.rodando());
        NcmExecucao ultima = NcmExecucao.find("order by id desc").firstResult();
        m.put("ultima", ultima);
        return m;
    }
}
