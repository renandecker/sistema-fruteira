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
    @Inject Auditor auditor;
    @ConfigProperty(name = "fruteira.gerente.pin") String pinGerente;
    /** true = crédito/débito só pode ser pago com transação TEF aprovada */
    @ConfigProperty(name = "fruteira.tef.obrigatorio", defaultValue = "false") boolean tefObrigatorio;
    /** true = o meio PIX só vale com Pix confirmado pelo PSP (pixId) */
    @ConfigProperty(name = "fruteira.pix.obrigatorio", defaultValue = "false") boolean pixObrigatorio;

    /** pesoBalanca=true só pode ser enviado pelo agente de balança do PDV (leitura por cabo). */
    public record ItemReq(Long produtoId, BigDecimal quantidade, boolean pesoBalanca) {}
    public record PagReq(String meio, BigDecimal valor, Long tefId, Long pixId) {}
    public record VendaReq(String cpf, boolean atacado, List<ItemReq> itens, List<PagReq> pagamentos) {}

    @POST @Transactional
    public Venda finalizar(VendaReq req) {
        Venda v = new Venda(); v.cpfCliente = req.cpf();
        for (ItemReq ir : req.itens()) {
            ProdutoDTO p = catalogo.buscar(ir.produtoId());
            if (Boolean.FALSE.equals(p.ativo())) throw new WebApplicationException("Produto desativado: " + p.nome(), 422);              // preço SEMPRE vem do servidor
            if ("KG".equals(p.unidade()) && !ir.pesoBalanca())
                throw new WebApplicationException("Peso digitado manualmente não é permitido para produto de balança", 422);
            Item i = new Item(); i.venda = v; i.produtoId = p.id(); i.nome = p.nome();
            i.quantidade = ir.quantidade(); i.precoUnit = p.preco(req.atacado());
            i.custoUnit = p.custoMedio() == null ? BigDecimal.ZERO : p.custoMedio();
            i.subtotal = i.quantidade.multiply(i.precoUnit).setScale(2, RoundingMode.HALF_UP); // ex.: 0,350kg x 7,90
            i.precoOriginal = p.precoBase(req.atacado());
            i.descontoTotal = i.quantidade.multiply(i.precoOriginal.subtract(i.precoUnit)).setScale(2, RoundingMode.HALF_UP);
            if (i.descontoTotal.signum() > 0) i.promocao = p.promocaoDescricao();
            v.desconto = v.desconto.add(i.descontoTotal);
            v.total = v.total.add(i.subtotal); v.itens.add(i);
        }
        BigDecimal pago = BigDecimal.ZERO; List<TefTransacao> tefs = new ArrayList<>(); List<PixCobranca> pixs = new ArrayList<>();
        for (PagReq pr : req.pagamentos()) {
            Pagamento pg = new Pagamento(); pg.venda = v; pg.meio = pr.meio(); pg.valor = pr.valor();
            boolean cartao = "CREDITO".equals(pr.meio()) || "DEBITO".equals(pr.meio());
            if (pr.tefId() != null) {                                  // pagamento aprovado no TEF: confere e guarda NSU/autorização/bandeira
                TefTransacao t = TefTransacao.findById(pr.tefId());
                if (!cartao) throw Http.erro(422, "Transação TEF só pode pagar crédito ou débito");
                if (t == null || !"APROVADA".equals(t.status) || t.vendaId != null) throw Http.erro(422, "Transação TEF inválida ou já utilizada");
                if (t.valor.compareTo(pr.valor()) != 0) throw Http.erro(422, "O valor difere do aprovado no TEF (R$ " + t.valor + ")");
                pg.tefId = t.id; pg.nsu = t.nsu; pg.autorizacao = t.autorizacao; pg.bandeira = t.bandeira; pg.adquirente = t.adquirente; pg.cnpjCredenciadora = t.cnpjCredenciadora;
                tefs.add(t);
            } else if (cartao && tefObrigatorio) throw Http.erro(422, "Pagamento em cartão exige transação TEF aprovada");
            if (pr.pixId() != null) {                                  // Pix recebido e confirmado pelo PSP (webhook): confere e vincula
                PixCobranca px = PixCobranca.findById(pr.pixId());
                if (!"PIX".equals(pr.meio())) throw Http.erro(422, "Cobrança Pix só pode pagar o meio PIX");
                if (px == null || !"CONCLUIDA".equals(px.status) || px.vendaId != null) throw Http.erro(422, "Pix inválido, ainda não confirmado ou já utilizado");
                if (px.valorPago.compareTo(pr.valor()) != 0) throw Http.erro(422, "O valor do Pix recebido (R$ " + px.valorPago + ") difere do pagamento");
                pg.pixTxid = px.txid; pg.pixE2e = px.endToEndId; pixs.add(px);
            } else if ("PIX".equals(pr.meio()) && pixObrigatorio) throw Http.erro(422, "Pagamento em Pix exige cobrança confirmada pelo PSP");
            pago = pago.add(pr.valor()); v.pagamentos.add(pg);
        }
        if (pago.compareTo(v.total) < 0) throw new WebApplicationException("Pagamento insuficiente", 422);
        v.status = Venda.Status.PAGA; v.persist();
        tefs.forEach(t -> { t.vendaId = v.id; t.atualizadoEm = LocalDateTime.now(); });   // vincula o cartão à venda
        pixs.forEach(px -> px.vendaId = v.id);                                              // vincula o Pix à venda
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade, "VENDA", "venda " + v.id)));
        return v;
    }
    /** Cancelamento exige PIN do gerente */
    @POST @Path("/{id}/cancelar") @Transactional
    public Venda cancelar(@PathParam("id") Long id, @HeaderParam("X-Gerente-Pin") String pin) {
        if (!pinGerente.equals(pin)) throw new ForbiddenException("Requer autorização do gerente");
        if (TefTransacao.count("vendaId = ?1 and status = ?2", id, "CONFIRMADA") > 0)
            throw Http.erro(409, "Venda paga com cartão (TEF): estorne o cartão na tela Transações TEF antes de cancelar a venda");
        if (PixCobranca.count("vendaId = ?1 and status = ?2", id, "CONCLUIDA") > 0)
            throw Http.erro(409, "Venda paga com Pix: devolva o Pix na tela Transações PIX antes de cancelar a venda");
        Venda v = Venda.findById(id); v.status = Venda.Status.CANCELADA;
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade.negate(), "AJUSTE", "estorno venda " + id)));
        auditor.registrar("CANCELAMENTO", "Venda", id, "Venda #" + id + " cancelada (total R$ " + v.total + ")", null, null);
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
    /** Ranking de produtos mais comprados (nº de vendas e quantidade). O PDV ordena a lista de produtos por ele. */
    public record Ranking(Long produtoId, long vendas, BigDecimal quantidade) {}
    @GET @Path("/ranking")
    public List<Ranking> ranking() {
        List<Object[]> l = Item.getEntityManager().createQuery(
            "select i.produtoId, count(i), sum(i.quantidade) from ItemVenda i where i.venda.status = :s group by i.produtoId order by count(i) desc, sum(i.quantidade) desc", Object[].class)
            .setParameter("s", Venda.Status.PAGA).getResultList();
        return l.stream().map(o -> new Ranking((Long) o[0], ((Number) o[1]).longValue(), (BigDecimal) o[2])).toList();
    }
    /** Curva ABC simplificada: faturamento por produto, ordenado desc */
    @GET @Path("/relatorio/abc")
    public List<Object[]> abc() {
        return Item.getEntityManager().createQuery(
            "select i.nome, sum(i.subtotal) from ItemVenda i where i.venda.status = :s group by i.nome order by sum(i.subtotal) desc", Object[].class).setParameter("s", Venda.Status.PAGA).getResultList();
    }
    /** Sangria cega: operador informa o contado; sistema compara só no backoffice */
    public record Fechamento(BigDecimal contadoDinheiro) {}
    @POST @Path("/caixa/fechamento")
    public java.util.Map<String, Object> fechar(Fechamento f) {
        BigDecimal esperado = Pagamento.getEntityManager().createQuery(
            "select coalesce(sum(p.valor),0) from PagamentoVenda p where p.meio='DINHEIRO' and p.venda.status = :s", BigDecimal.class).setParameter("s", Venda.Status.PAGA).getSingleResult();
        BigDecimal dif = f.contadoDinheiro().subtract(esperado);
        // fechamento cego: a diferença vai só para a trilha de auditoria (tela do gerente), nunca para o operador
        auditor.registrar("CADASTRO", "Fechamento de caixa", null, "Contado R$ " + f.contadoDinheiro() + " | esperado R$ " + esperado + " | diferença R$ " + dif, null, null);
        return java.util.Map.of("registrado", true);
    }
}
