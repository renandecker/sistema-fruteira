package br.com.fruteira.estoque;

import br.com.fruteira.estoque.Estoque.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/estoque") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class EstoqueResource {
    @Inject @RestClient CatalogoClient catalogo;
    @Inject Auditor auditor;

    public record Entrada(Long produtoId, BigDecimal qtdEmbalagem, BigDecimal fatorConversao, BigDecimal custoTotal, String documento, Long fornecedorId, String fornecedor) {}
    public record Baixa(Long produtoId, BigDecimal quantidade, Tipo tipo, String motivo) {}

    private Saldo saldo(Long produtoId) {
        Saldo s = Saldo.find("produtoId", produtoId).firstResult();
        if (s == null) { s = new Saldo(); s.produtoId = produtoId; s.persist(); }
        return s;
    }

    /** Ex.: 5 caixas de tomate, fator 20 (kg/cx), custo total R$ 550 -> +100 kg, custo médio recalculado */
    @POST @Path("/entrada") @Transactional
    public Saldo entrada(Entrada e) {
        BigDecimal qtdVenda = e.qtdEmbalagem().multiply(e.fatorConversao());
        Saldo s = saldo(e.produtoId());
        BigDecimal valorAtual = s.quantidade.multiply(s.custoMedio);
        BigDecimal novaQtd = s.quantidade.add(qtdVenda);
        BigDecimal custoUnit = e.custoTotal().divide(qtdVenda, 4, RoundingMode.HALF_UP);
        s.custoMedio = valorAtual.add(e.custoTotal()).divide(novaQtd, 4, RoundingMode.HALF_UP);
        s.quantidade = novaQtd;
        Movimento mv = mov(e.produtoId(), Tipo.ENTRADA, qtdVenda, custoUnit, e.documento());
        mv.fornecedorId = e.fornecedorId(); mv.fornecedor = e.fornecedor();
        catalogo.custo(e.produtoId(), s.custoMedio);
        auditor.registrar("CADASTRO", "Entrada de estoque", e.produtoId(), "Entrada de " + qtdVenda + " (doc: " + e.documento() + "), custo total R$ " + e.custoTotal() + (e.fornecedor() == null || e.fornecedor().isBlank() ? "" : ", fornecedor " + e.fornecedor()), null, e);
        return s;
    }
    /** Baixa por venda, perda, avaria ou transformação */
    @POST @Path("/baixa") @Transactional
    public Saldo baixa(Baixa b) {
        Saldo s = saldo(b.produtoId());
        s.quantidade = s.quantidade.subtract(b.quantidade());
        mov(b.produtoId(), b.tipo(), b.quantidade().negate(), s.custoMedio, b.motivo());
        if (b.tipo() == Tipo.PERDA || b.tipo() == Tipo.AVARIA)   // vendas e estornos são automáticos: não entram na auditoria
            auditor.registrar("BAIXA", "Estoque (" + b.tipo().name().toLowerCase() + ")", b.produtoId(), "Baixa de " + b.quantidade() + " por " + b.tipo().name().toLowerCase() + (b.motivo() == null || b.motivo().isBlank() ? "" : ": " + b.motivo()), null, b);
        return s;
    }
    /** Fracionamento: abacaxi inteiro -> bandeja picada */
    public record Producao(Long insumoId, BigDecimal qtdInsumo, Long produtoId, BigDecimal qtdProduto) {}
    @POST @Path("/producao") @Transactional
    public void producao(Producao p) {
        Saldo in = saldo(p.insumoId()), out = saldo(p.produtoId());
        BigDecimal custoTotal = in.custoMedio.multiply(p.qtdInsumo());
        in.quantidade = in.quantidade.subtract(p.qtdInsumo());
        BigDecimal nova = out.quantidade.add(p.qtdProduto());
        out.custoMedio = out.quantidade.multiply(out.custoMedio).add(custoTotal).divide(nova, 4, RoundingMode.HALF_UP);
        out.quantidade = nova;
        mov(p.insumoId(), Tipo.PRODUCAO_SAIDA, p.qtdInsumo().negate(), in.custoMedio, "produção");
        mov(p.produtoId(), Tipo.PRODUCAO_ENTRADA, p.qtdProduto(), out.custoMedio, "produção");
        auditor.registrar("EDICAO", "Produção/fracionamento", p.produtoId(), "Produção: " + p.qtdInsumo() + " do insumo #" + p.insumoId() + " viraram " + p.qtdProduto() + " do produto #" + p.produtoId(), null, p);
    }
    /** Últimas entradas de mercadoria (com o fornecedor) */
    public record EntradaDTO(Long id, Long produtoId, LocalDateTime data, BigDecimal quantidade, BigDecimal custoUnitario, String documento, String fornecedor) {}
    @GET @Path("/entradas")
    public List<EntradaDTO> entradas(@QueryParam("limit") @DefaultValue("50") int limit) {
        List<Movimento> l = Movimento.find("tipo = ?1 order by id desc", Tipo.ENTRADA).page(0, Math.min(Math.max(limit, 1), 200)).list();
        return l.stream().map(m -> new EntradaDTO(m.id, m.produtoId, m.data, m.quantidade, m.custoUnitario, m.motivo, m.fornecedor)).toList();
    }
    @GET @Path("/{produtoId}") public Saldo consulta(@PathParam("produtoId") Long id) { return saldo(id); }

    /** Relatório de perdas: quantidade e valor (a custo médio) por produto */
    @GET @Path("/relatorio/perdas")
    public List<Map<String, Object>> perdas() {
        List<Movimento> l = Movimento.list("tipo in ?1", List.of(Tipo.PERDA, Tipo.AVARIA));
        Map<Long, BigDecimal[]> acc = new TreeMap<>();
        for (Movimento m : l) {
            BigDecimal[] a = acc.computeIfAbsent(m.produtoId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(m.quantidade.abs()); a[1] = a[1].add(m.quantidade.abs().multiply(m.custoUnitario));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(Map.of("produtoId", k, "quantidade", v[0], "valorPerdido", v[1])));
        return out;
    }
    private Movimento mov(Long pid, Tipo t, BigDecimal q, BigDecimal c, String motivo) {
        Movimento m = new Movimento(); m.produtoId = pid; m.tipo = t; m.quantidade = q; m.custoUnitario = c; m.motivo = motivo; m.persist();
        return m;
    }
}
