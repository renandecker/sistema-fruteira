package br.com.fruteira.catalogo;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;

@Path("/produtos") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ProdutoResource {
    @Inject Auditor auditor;
    @Inject PromocaoService promocoes;
    @Inject PreCadastro preCadastro;

    /** Só o gerente enxerga produtos desativados (?inativos=true). A promoção vigente vem calculada em cada produto. */
    @GET public List<Produto> listar(@QueryParam("inativos") boolean inativos) {
        List<Produto> l = inativos && auditor.ehGerente() ? Produto.<Produto>listAll() : Produto.<Produto>list("ativo", true);
        promocoes.aplicar(l);
        return l;
    }
    @GET @Path("/{id}") public Produto buscar(@PathParam("id") Long id) {
        Produto p = Produto.findById(id);
        if (p == null) throw new NotFoundException();
        promocoes.aplicar(List.of(p));
        return p;
    }
    @GET @Path("/plu/{plu}") public Produto porPlu(@PathParam("plu") Integer plu) {
        Produto p = Produto.find("plu = ?1 and ativo = true", plu).firstResult();
        if (p == null) throw new NotFoundException("PLU não cadastrado");
        promocoes.aplicar(List.of(p));
        return p;
    }
    @GET @Path("/busca") public List<Produto> busca(@QueryParam("q") String q) {
        List<Produto> l = Produto.list("ativo = true and (lower(nome) like ?1 or codigoBarras = ?2)", "%" + q.toLowerCase() + "%", q);
        promocoes.aplicar(l);
        return l;
    }

    @POST @Transactional public Produto criar(Produto p) {
        p.categoria = validarCategoria(p.categoria, null);
        p.atalho = normalizarAtalho(p.atalho, null);
        p.codigoBarras = validarCodigoBarras(p.codigoBarras, null);
        p.ativo = true;
        p.persist();
        auditor.registrar("CADASTRO", "Produto", p.id, "Produto cadastrado: " + p.nome, null, p);
        return p;
    }

    @PUT @Path("/{id}") @Transactional public Produto atualizar(@PathParam("id") Long id, Produto in) {
        Produto p = buscar(id);
        String antes = auditor.snap(p);
        String categoria = validarCategoria(in.categoria, p.categoria);
        String atalho = normalizarAtalho(in.atalho, id);
        String codigoBarras = validarCodigoBarras(in.codigoBarras, id);
        p.nome = in.nome; p.unidade = in.unidade; p.precoVarejo = in.precoVarejo; p.precoAtacado = in.precoAtacado;
        p.plu = in.plu; p.codigoBarras = codigoBarras; p.categoria = categoria; p.fotoUrl = in.fotoUrl; p.imagem = in.imagem;
        p.taxaPerdaPct = in.taxaPerdaPct; p.ncm = in.ncm; p.atalho = atalho;
        promocoes.aplicar(List.of(p));
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

    /** Cadastra as frutas, legumes, verduras e temperos mais comuns (ignora o que já existe). */
    @POST @Path("/pre-cadastro")
    public PreCadastro.Resultado preCadastro() {
        PreCadastro.Resultado r = preCadastro.aplicar();
        auditor.registrar("CADASTRO", "Produto", null, "Pré-cadastro de frutas e verduras: " + r.produtosNovos() + " produto(s) e " + r.categoriasNovas() + " categoria(s) novos", null, null);
        return r;
    }

    /** Painel de margem por categoria/safra: recalcula o preço de toda a categoria a partir do custo médio e da perda */
    @POST @Path("/categoria/{cat}/margem") @Transactional
    public int ajustarMargem(@PathParam("cat") String cat, @QueryParam("margemPct") BigDecimal margem) {
        List<Produto> l = Produto.list("categoria = ?1 and ativo = true", cat);
        l.forEach(p -> p.precoVarejo = p.precoSugerido(margem));
        auditor.registrar("EDICAO", "Produto", "categoria:" + cat, "Reajuste de preços da categoria '" + cat + "' com margem de " + margem + "% (" + l.size() + " produtos)", null, null);
        return l.size();
    }
    /** Chamado pelo estoque-service ao receber compra (atualiza custo médio) */
    @PUT @Path("/{id}/custo-medio") @Transactional
    public void custo(@PathParam("id") Long id, @QueryParam("valor") BigDecimal v) {
        Produto p = Produto.findById(id);
        if (p == null) throw new NotFoundException();
        p.custoMedio = v;
    }

    /** Categoria: vazio = sem categoria; senão precisa existir e estar ativa (exceto se o produto já a tinha). */
    private String validarCategoria(String nova, String atual) {
        if (nova == null || nova.isBlank()) return null;
        String n = nova.trim();
        if (n.equals(atual)) return n;
        Categoria c = Categoria.find("lower(nome) = ?1", n.toLowerCase()).firstResult();
        if (c == null || Boolean.FALSE.equals(c.ativo)) throw Http.erro(422, "Categoria inexistente ou desativada: " + n);
        return c.nome;
    }
    /** Código de barras opcional; único entre produtos ativos (o leitor do caixa precisa identificar sem ambiguidade). */
    private String validarCodigoBarras(String cb, Long idAtual) {
        if (cb == null || cb.isBlank()) return null;
        String c = cb.trim();
        long usos = idAtual == null ? Produto.count("codigoBarras = ?1 and ativo = true", c)
                                    : Produto.count("codigoBarras = ?1 and ativo = true and id <> ?2", c, idAtual);
        if (usos > 0) throw Http.erro(409, "O código de barras " + c + " já está cadastrado em outro produto");
        return c;
    }
    /** Atalho é OPCIONAL. Vazio = sem atalho. Aceita 1 número ou letra, único entre os produtos ativos. */
    private String normalizarAtalho(String atalho, Long idAtual) {
        if (atalho == null || atalho.isBlank()) return null;
        String a = atalho.trim().toUpperCase();
        if (!a.matches("[0-9A-Z]")) throw Http.erro(422, "O atalho deve ser um único número (0-9) ou letra (A-Z)");
        if ("TNW".contains(a)) throw Http.erro(422, "Ctrl+" + a + " é reservado pelo navegador; escolha outra tecla");
        long usos = idAtual == null ? Produto.count("atalho = ?1 and ativo = true", a)
                                    : Produto.count("atalho = ?1 and ativo = true and id <> ?2", a, idAtual);
        if (usos > 0) throw Http.erro(409, "O atalho Ctrl+" + a + " já está em uso por outro produto");
        return a;
    }
}
