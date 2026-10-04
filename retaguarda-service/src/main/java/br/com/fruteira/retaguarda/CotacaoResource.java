package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Path("/cotacoes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class CotacaoResource {
    @Inject Auditor auditor;

    @GET public List<Cotacao> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Cotacao.<Cotacao>list("order by data desc, id desc")
                                               : Cotacao.<Cotacao>list("ativo = true order by data desc, id desc");
    }
    @POST @Transactional public Cotacao criar(Cotacao c) {
        c.ativo = true; c.persist();
        auditor.registrar("CADASTRO", "Cotação", c.id, "Cotação de " + c.produtoNome + " a R$ " + c.preco + " (" + c.fornecedor + ")", null, c);
        return c;
    }
    @DELETE @Path("/{id}") @Transactional public Cotacao desativar(@PathParam("id") Long id) {
        Cotacao c = achar(id);
        if (Boolean.FALSE.equals(c.ativo)) return c;
        c.ativo = false;
        auditor.registrar("DESATIVACAO", "Cotação", id, "Cotação desativada: " + c.produtoNome + " a R$ " + c.preco, null, null);
        return c;
    }
    @POST @Path("/{id}/reativar") @Transactional public Cotacao reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Cotacao c = achar(id); c.ativo = true;
        auditor.registrar("REATIVACAO", "Cotação", id, "Cotação reativada: " + c.produtoNome + " a R$ " + c.preco, null, null);
        return c;
    }
    private Cotacao achar(Long id) { Cotacao c = Cotacao.findById(id); if (c == null) throw new NotFoundException(); return c; }

    /** Mín/máx/média/última por produto (apenas cotações ativas) — base para negociar com o fornecedor */
    @GET @Path("/resumo")
    public List<Map<String, Object>> resumo() {
        Map<String, List<Cotacao>> g = new TreeMap<>();
        for (Cotacao c : Cotacao.<Cotacao>list("ativo", true)) g.computeIfAbsent(c.produtoNome, k -> new ArrayList<>()).add(c);
        List<Map<String, Object>> out = new ArrayList<>();
        g.forEach((nome, l) -> {
            BigDecimal min = l.stream().map(c -> c.preco).min(Comparator.naturalOrder()).get();
            BigDecimal max = l.stream().map(c -> c.preco).max(Comparator.naturalOrder()).get();
            BigDecimal media = l.stream().map(c -> c.preco).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(l.size()), 2, RoundingMode.HALF_UP);
            Cotacao ult = l.stream().max(Comparator.comparing((Cotacao c) -> c.data).thenComparing((Cotacao c) -> c.id)).get();
            out.add(Map.of("produto", nome, "min", min, "max", max, "media", media, "ultima", ult.preco,
                    "fornecedorUltima", ult.fornecedor == null ? "" : ult.fornecedor));
        });
        return out;
    }
}
