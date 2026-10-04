package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Auditoria;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.*;

/** Trilha de auditoria — SOMENTE inclusão e consulta (não há PUT/DELETE de propósito). */
@Path("/auditoria") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class AuditoriaResource {
    @Inject Auditor auditor;

    /** Recebe eventos dos outros microsserviços. Em produção a identidade é a do TOKEN (o campo "usuario" do corpo é ignorado). */
    @POST @Transactional
    public Map<String, Object> registrar(AuditoriaEvento e) {
        Auditor.Quem q = auditor.autenticado();
        if (q == null) q = new Auditor.Quem(e.usuario() == null ? "desconhecido" : e.usuario(), e.perfil() == null ? "-" : e.perfil());
        auditor.gravar(q, e.origem(), e.acao(), e.entidade(), e.entidadeId(), e.descricao(), e.antes(), e.depois());
        return Map.of("ok", true);
    }

    @GET
    public List<Auditoria> listar(@QueryParam("usuario") String usuario, @QueryParam("acao") String acao,
                                  @QueryParam("entidade") String entidade, @QueryParam("de") String de,
                                  @QueryParam("ate") String ate, @QueryParam("limit") @DefaultValue("200") int limit) {
        StringBuilder q = new StringBuilder("1=1");
        Map<String, Object> p = new HashMap<>();
        if (usuario != null && !usuario.isBlank()) { q.append(" and lower(usuario) like :usuario"); p.put("usuario", "%" + usuario.toLowerCase() + "%"); }
        if (acao != null && !acao.isBlank()) { q.append(" and acao = :acao"); p.put("acao", acao); }
        if (entidade != null && !entidade.isBlank()) { q.append(" and lower(entidade) like :entidade"); p.put("entidade", "%" + entidade.toLowerCase() + "%"); }
        if (de != null && !de.isBlank()) { q.append(" and data >= :de"); p.put("de", LocalDate.parse(de).atStartOfDay()); }
        if (ate != null && !ate.isBlank()) { q.append(" and data < :ate"); p.put("ate", LocalDate.parse(ate).plusDays(1).atStartOfDay()); }
        return Auditoria.find(q + " order by id desc", p).page(0, Math.min(Math.max(limit, 1), 1000)).list();
    }

    /** Valores para os filtros da tela */
    @GET @Path("/filtros")
    public Map<String, List<String>> filtros() {
        var em = Auditoria.getEntityManager();
        return Map.of(
            "usuarios", em.createQuery("select distinct a.usuario from Auditoria a order by a.usuario", String.class).getResultList(),
            "entidades", em.createQuery("select distinct a.entidade from Auditoria a order by a.entidade", String.class).getResultList());
    }
}
