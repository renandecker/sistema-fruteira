package br.com.fruteira.catalogo;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;

@Path("/produtos") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ProdutoResource {

    @GET public List<Produto> listar() { return Produto.list("ativo", true); }

    @GET @Path("/{id}") public Produto buscar(@PathParam("id") Long id) {
        Produto p = Produto.findById(id);
        if (p == null) throw new NotFoundException();
        return p;
    }
    @GET @Path("/plu/{plu}") public Produto porPlu(@PathParam("plu") Integer plu) {
        Produto p = Produto.find("plu", plu).firstResult();
        if (p == null) throw new NotFoundException("PLU não cadastrado");
        return p;
    }
    @GET @Path("/busca") public List<Produto> busca(@QueryParam("q") String q) {
        return Produto.list("lower(nome) like ?1 or codigoBarras = ?2", "%" + q.toLowerCase() + "%", q);
    }
    @POST @Transactional public Produto criar(Produto p) { p.persist(); return p; }

    @PUT @Path("/{id}") @Transactional public Produto atualizar(@PathParam("id") Long id, Produto in) {
        Produto p = buscar(id);
        p.nome = in.nome; p.unidade = in.unidade; p.precoVarejo = in.precoVarejo; p.precoAtacado = in.precoAtacado;
        p.plu = in.plu; p.codigoBarras = in.codigoBarras; p.categoria = in.categoria; p.fotoUrl = in.fotoUrl;
        p.taxaPerdaPct = in.taxaPerdaPct; p.ncm = in.ncm;
        return p;
    }
    /** Remarcação rápida por validade: POST /produtos/1/desconto-validade?pct=30 (pct=0 remove) */
    @POST @Path("/{id}/desconto-validade") @Transactional
    public Produto descontoValidade(@PathParam("id") Long id, @QueryParam("pct") BigDecimal pct) {
        Produto p = buscar(id);
        p.precoPromocional = pct.signum() == 0 ? null
            : p.precoVarejo.multiply(BigDecimal.ONE.subtract(pct.movePointLeft(2))).setScale(2, java.math.RoundingMode.HALF_UP);
        return p;
    }
    /** Painel de margem por categoria/safra: ajusta preço de toda a categoria com base no custo médio */
    @POST @Path("/categoria/{cat}/margem") @Transactional
    public int ajustarMargem(@PathParam("cat") String cat, @QueryParam("margemPct") BigDecimal margem) {
        List<Produto> l = Produto.list("categoria", cat);
        l.forEach(p -> p.precoVarejo = p.precoSugerido(margem));
        return l.size();
    }
    /** Chamado pelo estoque-service ao receber compra (atualiza custo médio) */
    @PUT @Path("/{id}/custo-medio") @Transactional
    public void custo(@PathParam("id") Long id, @QueryParam("valor") BigDecimal v) { buscar(id).custoMedio = v; }
}
