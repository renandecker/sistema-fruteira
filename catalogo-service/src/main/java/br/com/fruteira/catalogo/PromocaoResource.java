package br.com.fruteira.catalogo;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Promoções com prazo. "Excluir" = desativar; só o gerente vê as desativadas e as reativa. */
@Path("/promocoes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class PromocaoResource {
    @Inject Auditor auditor;

    public record NovaPromocao(List<Long> produtoIds, String tipo, BigDecimal valor, LocalDate inicio, LocalDate fim) {}
    public record Edicao(String tipo, BigDecimal valor, LocalDate inicio, LocalDate fim) {}
    public record PromocaoDTO(Long id, Long produtoId, String produtoNome, String tipo, BigDecimal valor, LocalDate inicio, LocalDate fim,
                              Boolean ativo, String situacao, BigDecimal precoOriginal, BigDecimal precoPromocional) {}

    @GET public List<PromocaoDTO> listar(@QueryParam("inativos") boolean inativos) {
        List<Promocao> l = inativos && auditor.ehGerente() ? Promocao.<Promocao>list("order by id desc") : Promocao.<Promocao>list("ativo = true order by id desc");
        Map<Long, Produto> prods = new HashMap<>();
        Produto.<Produto>listAll().forEach(p -> prods.put(p.id, p));
        LocalDate hoje = LocalDate.now();
        return l.stream().map(pr -> dto(pr, prods.get(pr.produtoId), hoje)).toList();
    }

    /** Marca vários produtos de uma vez com o mesmo desconto e prazo. */
    @POST @Transactional
    public List<PromocaoDTO> criar(NovaPromocao n) {
        validar(n.tipo(), n.valor(), n.inicio(), n.fim());
        if (n.produtoIds() == null || n.produtoIds().isEmpty()) throw Http.erro(422, "Marque ao menos um produto");
        LocalDate hoje = LocalDate.now();
        List<PromocaoDTO> out = new ArrayList<>();
        for (Long pid : new LinkedHashSet<>(n.produtoIds())) {
            Produto p = Produto.findById(pid);
            if (p == null || !p.ativo) throw Http.erro(422, "Produto inexistente ou desativado: #" + pid);
            checarPreco(n.tipo(), n.valor(), p);
            checarSobreposicao(pid, n.inicio(), n.fim(), null, p.nome);
            Promocao pr = new Promocao();
            pr.produtoId = pid; pr.tipo = n.tipo(); pr.valor = n.valor(); pr.inicio = n.inicio(); pr.fim = n.fim();
            pr.persist();
            auditor.registrar("CADASTRO", "Promoção", pr.id, "Promoção em " + p.nome + ": " + descricao(pr) + ", de " + pr.inicio + " a " + pr.fim, null, pr);
            out.add(dto(pr, p, hoje));
        }
        return out;
    }

    @PUT @Path("/{id}") @Transactional
    public PromocaoDTO editar(@PathParam("id") Long id, Edicao e) {
        Promocao pr = achar(id);
        Produto p = Produto.findById(pr.produtoId);
        validar(e.tipo(), e.valor(), e.inicio(), e.fim());
        String antes = auditor.snap(pr);
        String nome = p == null ? "#" + pr.produtoId : p.nome;
        if (p != null) checarPreco(e.tipo(), e.valor(), p);
        if (!Boolean.FALSE.equals(pr.ativo)) checarSobreposicao(pr.produtoId, e.inicio(), e.fim(), id, nome);
        pr.tipo = e.tipo(); pr.valor = e.valor(); pr.inicio = e.inicio(); pr.fim = e.fim();
        auditor.registrar("EDICAO", "Promoção", id, "Promoção alterada em " + nome + ": " + descricao(pr) + ", de " + pr.inicio + " a " + pr.fim, antes, pr);
        return dto(pr, p, LocalDate.now());
    }

    @DELETE @Path("/{id}") @Transactional
    public PromocaoDTO desativar(@PathParam("id") Long id) {
        Promocao pr = achar(id);
        Produto p = Produto.findById(pr.produtoId);
        if (!Boolean.FALSE.equals(pr.ativo)) {
            pr.ativo = false;
            auditor.registrar("DESATIVACAO", "Promoção", id, "Promoção desativada em " + (p == null ? "#" + pr.produtoId : p.nome) + " (" + descricao(pr) + ")", null, null);
        }
        return dto(pr, p, LocalDate.now());
    }

    @POST @Path("/{id}/reativar") @Transactional
    public PromocaoDTO reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Promocao pr = achar(id);
        Produto p = Produto.findById(pr.produtoId);
        String nome = p == null ? "#" + pr.produtoId : p.nome;
        checarSobreposicao(pr.produtoId, pr.inicio, pr.fim, id, nome);
        pr.ativo = true;
        auditor.registrar("REATIVACAO", "Promoção", id, "Promoção reativada em " + nome + " (" + descricao(pr) + ")", null, null);
        return dto(pr, p, LocalDate.now());
    }

    // ---------- regras ----------
    private void validar(String tipo, BigDecimal valor, LocalDate ini, LocalDate fim) {
        if (!"PERCENTUAL".equals(tipo) && !"PRECO_FIXO".equals(tipo)) throw Http.erro(422, "Tipo de promoção inválido");
        if (valor == null || valor.signum() <= 0) throw Http.erro(422, "Informe um valor maior que zero");
        if ("PERCENTUAL".equals(tipo) && valor.compareTo(new BigDecimal("100")) >= 0) throw Http.erro(422, "O desconto deve ser menor que 100%");
        if (ini == null || fim == null) throw Http.erro(422, "Informe o prazo da promoção (início e fim)");
        if (fim.isBefore(ini)) throw Http.erro(422, "O fim da promoção não pode ser antes do início");
    }
    private void checarPreco(String tipo, BigDecimal valor, Produto p) {
        if ("PRECO_FIXO".equals(tipo) && p.precoVarejo != null && valor.compareTo(p.precoVarejo) >= 0)
            throw Http.erro(422, "O preço promocional de " + p.nome + " deve ser menor que o preço normal (R$ " + p.precoVarejo + ")");
    }
    private void checarSobreposicao(Long produtoId, LocalDate ini, LocalDate fim, Long ignorarId, String nome) {
        long n = ignorarId == null
            ? Promocao.count("produtoId = ?1 and ativo = true and inicio <= ?2 and fim >= ?3", produtoId, fim, ini)
            : Promocao.count("produtoId = ?1 and ativo = true and inicio <= ?2 and fim >= ?3 and id <> ?4", produtoId, fim, ini, ignorarId);
        if (n > 0) throw Http.erro(409, nome + " já tem uma promoção ativa nesse período");
    }
    private String descricao(Promocao pr) {
        return "PERCENTUAL".equals(pr.tipo) ? pr.valor.stripTrailingZeros().toPlainString() + "% de desconto" : "preço promocional de R$ " + pr.valor;
    }
    private Promocao achar(Long id) { Promocao p = Promocao.findById(id); if (p == null) throw new NotFoundException(); return p; }
    private PromocaoDTO dto(Promocao pr, Produto p, LocalDate hoje) {
        BigDecimal orig = p == null ? null : p.precoVarejo;
        return new PromocaoDTO(pr.id, pr.produtoId, p == null ? "#" + pr.produtoId : p.nome, pr.tipo, pr.valor, pr.inicio, pr.fim,
                pr.ativo, pr.situacao(hoje), orig, orig == null ? null : pr.precoFinal(orig));
    }
}
