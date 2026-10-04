package br.com.fruteira.catalogo;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;

@Path("/produtos") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ProdutoResource {
    @Inject Auditor auditor;

    /** Só o gerente enxerga produtos desativados (?inativos=true). */
    @GET public List<Produto> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Produto.<Produto>listAll() : Produto.<Produto>list("ativo", true);
    }

    @GET @Path("/{id}") public Produto buscar(@PathParam("id") Long id) {
        Produto p = Produto.findById(id);
        if (p == null) throw new NotFoundException();
        return p;
    }
    @GET @Path("/plu/{plu}") public Produto porPlu(@PathParam("plu") Integer plu) {
        Produto p = Produto.find("plu = ?1 and ativo = true", plu).firstResult();
        if (p == null) throw new NotFoundException("PLU não cadastrado");
        return p;
    }
    @GET @Path("/busca") public List<Produto> busca(@QueryParam("q") String q) {
        return Produto.list("ativo = true and (lower(nome) like ?1 or codigoBarras = ?2)", "%" + q.toLowerCase() + "%", q);
    }
    @POST @Transactional public Produto criar(Produto p) {
        p.atalho = normalizarAtalho(p.atalho, null);
        p.persist();
        auditor.registrar("CADASTRO", "Produto", p.id, "Produto cadastrado: " + p.nome, null, p);
        return p;
    }

    @PUT @Path("/{id}") @Transactional public Produto atualizar(@PathParam("id") Long id, Produto in) {
        Produto p = buscar(id);
        String antes = auditor.snap(p);
        p.nome = in.nome; p.unidade = in.unidade; p.precoVarejo = in.precoVarejo; p.precoAtacado = in.precoAtacado;
        p.plu = in.plu; p.codigoBarras = in.codigoBarras; p.categoria = in.categoria; p.fotoUrl = in.fotoUrl;
        p.taxaPerdaPct = in.taxaPerdaPct; p.ncm = in.ncm;
        p.atalho = normalizarAtalho(in.atalho, id);
        auditor.registrar("EDICAO", "Produto", id, "Produto alterado: " + p.nome, antes, p);
        return p;
    }
    /** "Exclusão" lógica: desativa o produto, mantendo histórico, vendas e auditoria. O PLU continua reservado. */
    @DELETE @Path("/{id}") @Transactional
    public Produto desativar(@PathParam("id") Long id) {
        Produto p = buscar(id);
        if (!p.ativo) return p;
        p.ativo = false;
        auditor.registrar("DESATIVACAO", "Produto", id, "Produto desativado: " + p.nome, null, null);
        return p;
    }
    @POST @Path("/{id}/reativar") @Transactional
    public Produto reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Produto p = buscar(id);
        String aviso = "";
        if (p.atalho != null && Produto.count("atalho = ?1 and ativo = true and id <> ?2", p.atalho, id) > 0) {
            aviso = " (o atalho Ctrl+" + p.atalho + " já estava em uso e foi removido)";
            p.atalho = null;
        }
        p.ativo = true;
        auditor.registrar("REATIVACAO", "Produto", id, "Produto reativado: " + p.nome + aviso, null, null);
        return p;
    }
    /** Atalho é OPCIONAL. Vazio = sem atalho. Aceita 1 número ou letra, único entre os produtos ativos. */
    private String normalizarAtalho(String atalho, Long idAtual) {
        if (atalho == null || atalho.isBlank()) return null;
        String a = atalho.trim().toUpperCase();
        if (!a.matches("[0-9A-Z]")) throw erro(422, "O atalho deve ser um único número (0-9) ou letra (A-Z)");
        if ("TNW".contains(a)) throw erro(422, "Ctrl+" + a + " é reservado pelo navegador; escolha outra tecla");
        long usos = idAtual == null ? Produto.count("atalho = ?1 and ativo = true", a)
                                    : Produto.count("atalho = ?1 and ativo = true and id <> ?2", a, idAtual);
        if (usos > 0) throw erro(409, "O atalho Ctrl+" + a + " já está em uso por outro produto");
        return a;
    }
    private WebApplicationException erro(int status, String msg) {
        return new WebApplicationException(Response.status(status).entity(msg).type(MediaType.TEXT_PLAIN).build());
    }
    /** Remarcação rápida por validade: POST /produtos/1/desconto-validade?pct=30 (pct=0 remove) */
    @POST @Path("/{id}/desconto-validade") @Transactional
    public Produto descontoValidade(@PathParam("id") Long id, @QueryParam("pct") BigDecimal pct) {
        Produto p = buscar(id);
        String antes = auditor.snap(p);
        p.precoPromocional = pct.signum() == 0 ? null
            : p.precoVarejo.multiply(BigDecimal.ONE.subtract(pct.movePointLeft(2))).setScale(2, java.math.RoundingMode.HALF_UP);
        auditor.registrar("EDICAO", "Produto", id, (pct.signum() == 0 ? "Promoção removida de " : "Desconto por validade de " + pct + "% em ") + p.nome, antes, p);
        return p;
    }
    /** Painel de margem por categoria/safra: ajusta preço de toda a categoria com base no custo médio */
    @POST @Path("/categoria/{cat}/margem") @Transactional
    public int ajustarMargem(@PathParam("cat") String cat, @QueryParam("margemPct") BigDecimal margem) {
        List<Produto> l = Produto.list("categoria = ?1 and ativo = true", cat);
        l.forEach(p -> p.precoVarejo = p.precoSugerido(margem));
        auditor.registrar("EDICAO", "Produto", "categoria:" + cat, "Reajuste de preços da categoria '" + cat + "' com margem de " + margem + "% (" + l.size() + " produtos)", null, null);
        return l.size();
    }
    /** Chamado pelo estoque-service ao receber compra (atualiza custo médio) */
    @PUT @Path("/{id}/custo-medio") @Transactional
    public void custo(@PathParam("id") Long id, @QueryParam("valor") BigDecimal v) { buscar(id).custoMedio = v; }
}
