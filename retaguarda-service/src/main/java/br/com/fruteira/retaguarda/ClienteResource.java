package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Path("/clientes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ClienteResource {
    static final BigDecimal CASHBACK = new BigDecimal("0.02"); // 2% da compra
    @Inject Auditor auditor;

    @GET public List<Cliente> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Cliente.<Cliente>listAll() : Cliente.<Cliente>list("ativo", true);
    }
    @POST @Transactional public Cliente criar(Cliente c) {
        Cliente ex = Cliente.find("cpf", c.cpf).firstResult();
        if (ex != null) throw new WebApplicationException(Boolean.FALSE.equals(ex.ativo)
            ? "CPF pertence a um cliente desativado (somente o gerente pode reativar)" : "CPF já cadastrado", 409);
        c.ativo = true; c.persist();
        auditor.registrar("CADASTRO", "Cliente", c.id, "Cliente cadastrado: " + c.nome, null, c);
        return c;
    }
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
        auditor.registrar("EDICAO", "Cliente", c.id, "Resgate de cashback de R$ " + valor + " — " + c.nome, null, null);
        return c;
    }
    @DELETE @Path("/{id}") @Transactional public Cliente desativar(@PathParam("id") Long id) {
        Cliente c = porId(id);
        if (Boolean.FALSE.equals(c.ativo)) return c;
        c.ativo = false;
        auditor.registrar("DESATIVACAO", "Cliente", id, "Cliente desativado: " + c.nome, null, null);
        return c;
    }
    @POST @Path("/{id}/reativar") @Transactional public Cliente reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Cliente c = porId(id); c.ativo = true;
        auditor.registrar("REATIVACAO", "Cliente", id, "Cliente reativado: " + c.nome, null, null);
        return c;
    }
    private Cliente porId(Long id) { Cliente c = Cliente.findById(id); if (c == null) throw new NotFoundException(); return c; }
    /** Cliente desativado não é encontrado no caixa (não acumula nem resgata cashback). */
    private Cliente achar(String cpf) {
        Cliente c = Cliente.find("cpf", cpf).firstResult();
        if (c == null || Boolean.FALSE.equals(c.ativo)) throw new NotFoundException("Cliente não cadastrado");
        return c;
    }
}
