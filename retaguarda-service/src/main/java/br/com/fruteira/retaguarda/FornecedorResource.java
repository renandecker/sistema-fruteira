package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Fornecedores nunca são apagados: "excluir" = desativar. Só o gerente vê e reativa os desativados. */
@Path("/fornecedores") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class FornecedorResource {
    @Inject Auditor auditor;

    @GET public List<Fornecedor> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Fornecedor.<Fornecedor>listAll() : Fornecedor.<Fornecedor>list("ativo", true);
    }
    @POST @Transactional public Fornecedor criar(Fornecedor f) {
        f.ativo = true; f.persist();
        auditor.registrar("CADASTRO", "Fornecedor", f.id, "Fornecedor cadastrado: " + f.nome, null, f);
        return f;
    }
    @DELETE @Path("/{id}") @Transactional public Fornecedor desativar(@PathParam("id") Long id) {
        Fornecedor f = achar(id);
        if (Boolean.FALSE.equals(f.ativo)) return f;
        f.ativo = false;
        auditor.registrar("DESATIVACAO", "Fornecedor", id, "Fornecedor desativado: " + f.nome, null, null);
        return f;
    }
    @POST @Path("/{id}/reativar") @Transactional public Fornecedor reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Fornecedor f = achar(id); f.ativo = true;
        auditor.registrar("REATIVACAO", "Fornecedor", id, "Fornecedor reativado: " + f.nome, null, null);
        return f;
    }
    private Fornecedor achar(Long id) { Fornecedor f = Fornecedor.findById(id); if (f == null) throw new NotFoundException(); return f; }
}
