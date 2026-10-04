package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Conta;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;

@Path("/contas") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ContaResource {
    @GET public List<Conta> listar(@QueryParam("tipo") String tipo) {
        return tipo == null ? Conta.list("order by vencimento") : Conta.list("tipo = ?1 order by vencimento", tipo);
    }
    @POST @Transactional public Conta criar(Conta c) { c.pago = false; c.persist(); return c; }
    @POST @Path("/{id}/baixar") @Transactional public Conta baixar(@PathParam("id") Long id) {
        Conta c = Conta.findById(id);
        if (c == null) throw new NotFoundException();
        c.pago = true; c.dataPagamento = LocalDate.now();
        return c;
    }
}
