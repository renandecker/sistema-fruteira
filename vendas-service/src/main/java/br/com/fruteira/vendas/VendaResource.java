package br.com.fruteira.vendas;

import br.com.fruteira.vendas.Clients.*;
import br.com.fruteira.vendas.Venda.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/vendas") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class VendaResource {
    @Inject @RestClient Clients.Catalogo catalogo;
    @Inject @RestClient Clients.Estoque estoque;
    @ConfigProperty(name = "fruteira.gerente.pin") String pinGerente;

    /** pesoBalanca=true só pode ser enviado pelo agente de balança do PDV (leitura por cabo). */
    public record ItemReq(Long produtoId, BigDecimal quantidade, boolean pesoBalanca) {}
    public record PagReq(String meio, BigDecimal valor) {}
    public record VendaReq(String cpf, boolean atacado, List<ItemReq> itens, List<PagReq> pagamentos) {}

    @POST @Transactional
    public Venda finalizar(VendaReq req) {
        Venda v = new Venda(); v.cpfCliente = req.cpf();
        for (ItemReq ir : req.itens()) {
            ProdutoDTO p = catalogo.buscar(ir.produtoId());              // preço SEMPRE vem do servidor
            if ("KG".equals(p.unidade()) && !ir.pesoBalanca())
                throw new WebApplicationException("Peso digitado manualmente não é permitido para produto de balança", 422);
            Item i = new Item(); i.venda = v; i.produtoId = p.id(); i.nome = p.nome();
            i.quantidade = ir.quantidade(); i.precoUnit = p.preco(req.atacado());
            i.custoUnit = p.custoMedio() == null ? BigDecimal.ZERO : p.custoMedio();
            i.subtotal = i.quantidade.multiply(i.precoUnit).setScale(2, RoundingMode.HALF_UP); // ex.: 0,350kg x 7,90
            v.total = v.total.add(i.subtotal); v.itens.add(i);
        }
        BigDecimal pago = BigDecimal.ZERO;
        for (PagReq pr : req.pagamentos()) {
            Pagamento pg = new Pagamento(); pg.venda = v; pg.meio = pr.meio(); pg.valor = pr.valor();
            pago = pago.add(pr.valor()); v.pagamentos.add(pg);
        }
        if (pago.compareTo(v.total) < 0) throw new WebApplicationException("Pagamento insuficiente", 422);
        v.status = Venda.Status.PAGA; v.persist();
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade, "VENDA", "venda " + v.id)));
        return v;
    }
    /** Cancelamento exige PIN do gerente */
    @POST @Path("/{id}/cancelar") @Transactional
    public Venda cancelar(@PathParam("id") Long id, @HeaderParam("X-Gerente-Pin") String pin) {
        if (!pinGerente.equals(pin)) throw new ForbiddenException("Requer autorização do gerente");
        Venda v = Venda.findById(id); v.status = Venda.Status.CANCELADA;
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade.negate(), "AJUSTE", "estorno venda " + id)));
        return v;
    }
    public record VendaResumo(Long id, LocalDateTime data, BigDecimal total, String status, String cpf) {}
    @GET public List<VendaResumo> recentes(@QueryParam("limit") @DefaultValue("30") int limit) {
        List<Venda> l = Venda.find("order by id desc").page(0, limit).list();
        return l.stream().map(v -> new VendaResumo(v.id, v.data, v.total, v.status.name(), v.cpfCliente)).toList();
    }
    /** Receita e CMV (custo médio no momento da venda) — base do DRE */
    public record Resumo(BigDecimal receita, BigDecimal cmv, long vendas) {}
    @GET @Path("/relatorio/resumo") @Transactional
    public Resumo resumo() {
        BigDecimal rec = BigDecimal.ZERO, cmv = BigDecimal.ZERO; long n = 0;
        for (Venda v : Venda.<Venda>list("status", Venda.Status.PAGA)) {
            n++; rec = rec.add(v.total);
            for (Item i : v.itens) if (i.custoUnit != null) cmv = cmv.add(i.quantidade.multiply(i.custoUnit));
        }
        return new Resumo(rec, cmv.setScale(2, RoundingMode.HALF_UP), n);
    }
    /** Média de quantidade vendida por produto num dia da semana (1=seg ... 7=dom) — base da sugestão de compras */
    public record Media(Long produtoId, String nome, BigDecimal mediaQtd) {}
    @GET @Path("/relatorio/media-dia-semana") @Transactional
    public List<Media> media(@QueryParam("dow") int dow) {
        Map<Long, String> nomes = new HashMap<>(); Map<Long, BigDecimal> soma = new HashMap<>(); Set<LocalDate> dias = new HashSet<>();
        for (Venda v : Venda.<Venda>list("status", Venda.Status.PAGA)) {
            if (v.data.getDayOfWeek().getValue() != dow) continue;
            dias.add(v.data.toLocalDate());
            for (Item i : v.itens) { nomes.put(i.produtoId, i.nome); soma.merge(i.produtoId, i.quantidade, BigDecimal::add); }
        }
        BigDecimal n = BigDecimal.valueOf(Math.max(1, dias.size()));
        return soma.entrySet().stream()
            .map(e -> new Media(e.getKey(), nomes.get(e.getKey()), e.getValue().divide(n, 3, RoundingMode.HALF_UP)))
            .sorted(Comparator.comparing(Media::mediaQtd).reversed()).toList();
    }
    /** Curva ABC simplificada: faturamento por produto, ordenado desc */
    @GET @Path("/relatorio/abc")
    public List<Object[]> abc() {
        return Item.getEntityManager().createQuery(
            "select i.nome, sum(i.subtotal) from ItemVenda i where i.venda.status='PAGA' group by i.nome order by sum(i.subtotal) desc", Object[].class).getResultList();
    }
    /** Sangria cega: operador informa o contado; sistema compara só no backoffice */
    public record Fechamento(BigDecimal contadoDinheiro) {}
    @POST @Path("/caixa/fechamento")
    public java.util.Map<String, Object> fechar(Fechamento f) {
        BigDecimal esperado = Pagamento.getEntityManager().createQuery(
            "select coalesce(sum(p.valor),0) from PagamentoVenda p where p.meio='DINHEIRO' and p.venda.status='PAGA'", BigDecimal.class).getSingleResult();
        return java.util.Map.of("registrado", true, "diferenca", f.contadoDinheiro().subtract(esperado)); // expor só ao gerente
    }
}
