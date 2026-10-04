package br.com.fruteira.catalogo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Registra cadastro/edição/exclusão na auditoria central (retaguarda-service).
 * Quem fez: em PRODUÇÃO vem do token do Keycloak; em DESENVOLVIMENTO, dos cabeçalhos X-Usuario/X-Perfil enviados pelo front.
 * Best-effort: se a auditoria estiver fora do ar, a operação de negócio NÃO é bloqueada (fica um aviso no log).
 */
@ApplicationScoped
public class Auditor {
    private static final Logger LOG = Logger.getLogger(Auditor.class);
    @Inject @RestClient AuditoriaClient client;
    @Inject SecurityIdentity identity;
    @Inject RoutingContext rc;
    @Inject ObjectMapper mapper;

    /** Serializa o objeto em JSON (use ANTES de alterar, para guardar o "antes"). */
    public String snap(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
    /** true se o usuário atual é gerente (token em produção; cabeçalho X-Perfil em desenvolvimento) */
    public boolean ehGerente() {
        if (identity != null && !identity.isAnonymous()) return identity.getRoles().contains("gerente");
        return "gerente".equalsIgnoreCase(rc.request().getHeader("X-Perfil"));
    }
    public void registrar(String acao, String entidade, Object id, String descricao, Object antes, Object depois) {
        try {
            String usuario, perfil;
            if (identity != null && !identity.isAnonymous()) {
                var r = identity.getRoles();
                usuario = identity.getPrincipal().getName();
                perfil = r.contains("gerente") ? "gerente" : r.contains("supervisor") ? "supervisor" : "operador";
            } else {
                String u = rc.request().getHeader("X-Usuario"), p = rc.request().getHeader("X-Perfil");
                usuario = u == null || u.isBlank() ? "desconhecido" : u; perfil = p == null || p.isBlank() ? "-" : p;
            }
            client.registrar(new AuditoriaEvento(acao, entidade, id == null ? null : String.valueOf(id), descricao,
                    snap(antes), snap(depois), usuario, perfil, "catalogo-service"));
        } catch (Exception e) {
            LOG.warn("Falha ao registrar auditoria (" + acao + " " + entidade + "): " + e.getMessage());
        }
    }
}
