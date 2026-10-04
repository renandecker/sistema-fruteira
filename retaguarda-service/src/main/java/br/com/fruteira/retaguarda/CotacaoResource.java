package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Cotacao;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.util.*;

@Path("/cotacoes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class CotacaoResource {
    @GET public List<Cotacao> listar() { return Cotacao.list("order by data desc, id desc"); }
    @POST @Transactional public Cotacao criar(Cotacao c) { c.persist(); return c; }

    /** Mín/máx/média/última por produto — base para negociar com o fornecedor */
    @GET @Path("/resumo")
    public List<Map<String, Object>> resumo() {
        Map<String, List<Cotacao>> g = new TreeMap<>();
        for (Cotacao c : Cotacao.<Cotacao>listAll()) g.computeIfAbsent(c.produtoNome, k -> new ArrayList<>()).add(c);
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
