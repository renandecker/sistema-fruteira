package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Path("/contas") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ContaResource {
    @Inject Auditor auditor;

    @GET public List<Conta> listar(@QueryParam("tipo") String tipo, @QueryParam("inativos") boolean inativos) {
        StringBuilder q = new StringBuilder("1=1"); List<Object> p = new ArrayList<>();
        if (!(inativos && auditor.ehGerente())) q.append(" and ativo = true");
        if (tipo != null && !tipo.isBlank()) { p.add(tipo); q.append(" and tipo = ?").append(p.size()); }
        return Conta.list(q + " order by vencimento", p.toArray());
    }
    @POST @Transactional public Conta criar(Conta c) {
        c.pago = false; c.ativo = true; c.persist();
        auditor.registrar("CADASTRO", "Conta " + (c.tipo == null ? "" : c.tipo.toLowerCase()), c.id, "Conta lançada: " + c.descricao + " — R$ " + c.valor + ", vence " + c.vencimento, null, c);
        return c;
    }
    @POST @Path("/{id}/baixar") @Transactional public Conta baixar(@PathParam("id") Long id) {
        Conta c = achar(id);
        if (Boolean.FALSE.equals(c.ativo)) throw new WebApplicationException("Conta desativada", 422);
        c.pago = true; c.dataPagamento = LocalDate.now();
        auditor.registrar("EDICAO", "Conta " + (c.tipo == null ? "" : c.tipo.toLowerCase()), id, "Baixa da conta '" + c.descricao + "' (R$ " + c.valor + ")", null, null);
        return c;
    }
    /** Lançamento errado? Desative. Conta já paga não pode ser desativada (distorceria o DRE). */
    @DELETE @Path("/{id}") @Transactional public Conta desativar(@PathParam("id") Long id) {
        Conta c = achar(id);
        if (c.pago) throw new WebApplicationException("Conta já paga não pode ser desativada", 422);
        if (Boolean.FALSE.equals(c.ativo)) return c;
        c.ativo = false;
        auditor.registrar("DESATIVACAO", "Conta " + (c.tipo == null ? "" : c.tipo.toLowerCase()), id, "Conta desativada: " + c.descricao + " (R$ " + c.valor + ")", null, null);
        return c;
    }
    @POST @Path("/{id}/reativar") @Transactional public Conta reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Conta c = achar(id); c.ativo = true;
        auditor.registrar("REATIVACAO", "Conta " + (c.tipo == null ? "" : c.tipo.toLowerCase()), id, "Conta reativada: " + c.descricao + " (R$ " + c.valor + ")", null, null);
        return c;
    }
    private Conta achar(Long id) { Conta c = Conta.findById(id); if (c == null) throw new NotFoundException(); return c; }
}
