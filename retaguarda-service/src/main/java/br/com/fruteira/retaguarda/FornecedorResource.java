package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Fornecedor;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/fornecedores") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class FornecedorResource {
    @GET public List<Fornecedor> listar() { return Fornecedor.listAll(); }
    @POST @Transactional public Fornecedor criar(Fornecedor f) { f.persist(); return f; }
    @DELETE @Path("/{id}") @Transactional public void excluir(@PathParam("id") Long id) { Fornecedor.deleteById(id); }
}
