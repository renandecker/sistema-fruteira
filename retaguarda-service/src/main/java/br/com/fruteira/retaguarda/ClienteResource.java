package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Cliente;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.util.List;

@Path("/clientes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ClienteResource {
    static final BigDecimal CASHBACK = new BigDecimal("0.02"); // 2% da compra

    @GET public List<Cliente> listar() { return Cliente.listAll(); }
    @POST @Transactional public Cliente criar(Cliente c) { c.persist(); return c; }
    @GET @Path("/cpf/{cpf}") public Cliente porCpf(@PathParam("cpf") String cpf) { return achar(cpf); }

    /** Acumula cashback após a venda */
    @POST @Path("/cpf/{cpf}/compra") @Transactional
    public Cliente compra(@PathParam("cpf") String cpf, @QueryParam("valor") BigDecimal valor) {
        Cliente c = achar(cpf);
        c.cashback = c.cashback.add(valor.multiply(CASHBACK)).setScale(2, RoundingMode.HALF_UP);
        return c;
    }
    @POST @Path("/cpf/{cpf}/resgatar") @Transactional
    public Cliente resgatar(@PathParam("cpf") String cpf, @QueryParam("valor") BigDecimal valor) {
        Cliente c = achar(cpf);
        if (c.cashback.compareTo(valor) < 0) throw new WebApplicationException("Saldo de cashback insuficiente", 422);
        c.cashback = c.cashback.subtract(valor);
        return c;
    }
    private Cliente achar(String cpf) {
        Cliente c = Cliente.find("cpf", cpf).firstResult();
        if (c == null) throw new NotFoundException("Cliente não cadastrado");
        return c;
    }
}
