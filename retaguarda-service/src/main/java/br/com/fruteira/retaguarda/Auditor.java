package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Auditoria;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Grava a trilha de auditoria. Os métodos chamadores já são @Transactional: a auditoria entra na MESMA transação do negócio. */
@ApplicationScoped
public class Auditor {
    public record Quem(String usuario, String perfil) {}
    @Inject SecurityIdentity identity;
    @Inject RoutingContext rc;
    @Inject ObjectMapper mapper;

    /** Usuário do token Keycloak (produção) ou null se não autenticado (desenvolvimento). */
    public Quem autenticado() {
        if (identity == null || identity.isAnonymous()) return null;
        var r = identity.getRoles();
        return new Quem(identity.getPrincipal().getName(), r.contains("gerente") ? "gerente" : r.contains("supervisor") ? "supervisor" : "operador");
    }
    public Quem quem() {
        Quem q = autenticado();
        if (q != null) return q;
        String u = rc.request().getHeader("X-Usuario"), p = rc.request().getHeader("X-Perfil");
        return new Quem(u == null || u.isBlank() ? "desconhecido" : u, p == null || p.isBlank() ? "-" : p);
    }
    /** true se o usuário atual é gerente (token em produção; cabeçalho X-Perfil em desenvolvimento) */
    public boolean ehGerente() {
        if (identity != null && !identity.isAnonymous()) return identity.getRoles().contains("gerente");
        return "gerente".equalsIgnoreCase(rc.request().getHeader("X-Perfil"));
    }
    public String snap(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
    public void registrar(String acao, String entidade, Object id, String descricao, Object antes, Object depois) {
        gravar(quem(), "retaguarda-service", acao, entidade, id == null ? null : String.valueOf(id), descricao, snap(antes), snap(depois));
    }
    public Auditoria gravar(Quem q, String origem, String acao, String entidade, String entidadeId, String descricao, String antes, String depois) {
        Auditoria a = new Auditoria();
        a.usuario = q.usuario(); a.perfil = q.perfil(); a.origem = origem; a.acao = acao; a.entidade = entidade; a.entidadeId = entidadeId;
        a.descricao = cortar(descricao, 1000); a.antes = cortar(antes, 4000); a.depois = cortar(depois, 4000);
        a.persist();
        return a;
    }
    private static String cortar(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
}
