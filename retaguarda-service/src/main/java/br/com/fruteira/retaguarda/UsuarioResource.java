package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Usuario;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.security.MessageDigest;
import java.util.*;

/** Cadastro simples de usuários/PIN. Em produção, substituir por Keycloak (quarkus-oidc). */
@Path("/usuarios") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class UsuarioResource {
    public record Pin(String pin) {}

    @GET public List<Usuario> listar() { return Usuario.listAll(); }

    @POST @Transactional public Usuario criar(Usuario u) {
        if (u.pin == null || u.pin.length() < 4) throw new WebApplicationException("PIN mínimo de 4 dígitos", 422);
        u.pinHash = hash(u.pin); u.persist(); return u;
    }
    @POST @Path("/{id}/ativo") @Transactional public Usuario ativo(@PathParam("id") Long id, @QueryParam("valor") boolean valor) {
        Usuario u = Usuario.findById(id); if (u == null) throw new NotFoundException(); u.ativo = valor; return u;
    }
    /** Autorização de supervisor/gerente no caixa (cancelamento, desconto, estorno) */
    @POST @Path("/validar")
    public Map<String, String> validar(Pin p) {
        Usuario u = p.pin() == null ? null : Usuario.find("pinHash = ?1 and ativo = true", hash(p.pin())).firstResult();
        if (u == null) throw new ForbiddenException("PIN inválido");
        return Map.of("nome", u.nome, "perfil", u.perfil);
    }
    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("fruteira:" + s).getBytes())); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
