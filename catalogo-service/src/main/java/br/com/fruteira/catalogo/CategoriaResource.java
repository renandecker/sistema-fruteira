package br.com.fruteira.catalogo;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Categorias dos produtos (viram abas no PDV). "Excluir" = desativar; só o gerente vê e reativa as desativadas. */
@Path("/categorias") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class CategoriaResource {
    @Inject Auditor auditor;
    public record Entrada(String nome, Integer ordem) {}

    @GET public List<Categoria> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Categoria.<Categoria>list("order by ordem, nome") : Categoria.<Categoria>list("ativo = true order by ordem, nome");
    }
    @POST @Transactional public Categoria criar(Entrada e) {
        String nome = limpar(e.nome());
        Categoria ex = Categoria.find("lower(nome) = ?1", nome.toLowerCase()).firstResult();
        if (ex != null) throw Http.erro(409, Boolean.FALSE.equals(ex.ativo)
            ? "Existe uma categoria desativada com esse nome (somente o gerente pode reativar)" : "Já existe uma categoria com esse nome");
        Categoria c = new Categoria();
        c.nome = nome; c.ordem = e.ordem() != null ? e.ordem() : (int) Categoria.count() + 1;
        c.persist();
        auditor.registrar("CADASTRO", "Categoria", c.id, "Categoria cadastrada: " + c.nome, null, c);
        return c;
    }
    /** Renomear atualiza também os produtos da categoria. */
    @PUT @Path("/{id}") @Transactional public Categoria editar(@PathParam("id") Long id, Entrada e) {
        Categoria c = achar(id);
        String antes = auditor.snap(c);
        String nome = limpar(e.nome());
        if (Categoria.count("lower(nome) = ?1 and id <> ?2", nome.toLowerCase(), id) > 0) throw Http.erro(409, "Já existe outra categoria com esse nome");
        String antigo = c.nome;
        c.nome = nome;
        if (e.ordem() != null) c.ordem = e.ordem();
        if (!antigo.equals(nome)) Produto.update("categoria = ?1 where categoria = ?2", nome, antigo);
        auditor.registrar("EDICAO", "Categoria", id, antigo.equals(nome) ? "Categoria reordenada: " + nome : "Categoria renomeada: " + antigo + " → " + nome, antes, c);
        return c;
    }
    @DELETE @Path("/{id}") @Transactional public Categoria desativar(@PathParam("id") Long id) {
        Categoria c = achar(id);
        if (Boolean.FALSE.equals(c.ativo)) return c;
        c.ativo = false;
        auditor.registrar("DESATIVACAO", "Categoria", id, "Categoria desativada: " + c.nome + " (os produtos aparecem em \"Outros\" no PDV)", null, null);
        return c;
    }
    @POST @Path("/{id}/reativar") @Transactional public Categoria reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Categoria c = achar(id); c.ativo = true;
        auditor.registrar("REATIVACAO", "Categoria", id, "Categoria reativada: " + c.nome, null, null);
        return c;
    }
    private Categoria achar(Long id) { Categoria c = Categoria.findById(id); if (c == null) throw new NotFoundException(); return c; }
    private String limpar(String nome) {
        if (nome == null || nome.isBlank()) throw Http.erro(422, "Informe o nome da categoria");
        String n = nome.trim();
        if (n.length() > 60) throw Http.erro(422, "O nome da categoria deve ter até 60 caracteres");
        return n;
    }
}
