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
    @Inject @RestClient Clients.Caderno caderno;
    @Inject Auditor auditor;
    @ConfigProperty(name = "fruteira.gerente.pin") String pinGerente;
    /** true = crédito/débito só pode ser pago com transação TEF aprovada */
    @ConfigProperty(name = "fruteira.tef.obrigatorio", defaultValue = "false") boolean tefObrigatorio;
    /** true = o meio PIX só vale com Pix confirmado pelo PSP (pixId) */
    @ConfigProperty(name = "fruteira.pix.obrigatorio", defaultValue = "false") boolean pixObrigatorio;
    /** NCM válido nos itens: sempre que houver pagamento TEF (padrão) e, opcionalmente, em toda venda */
    @ConfigProperty(name = "fruteira.ncm.obrigatorio-tef", defaultValue = "true") boolean ncmObrigatorioTef;
    @ConfigProperty(name = "fruteira.ncm.obrigatorio", defaultValue = "false") boolean ncmObrigatorio;

    /** pesoBalanca=true só pode ser enviado pelo agente de balança do PDV (leitura por cabo). */
    public record ItemReq(Long produtoId, BigDecimal quantidade, boolean pesoBalanca) {}
    public record PagReq(String meio, BigDecimal valor, Long tefId, Long pixId, String operadora) {}
    public record VendaReq(String cpf, boolean atacado, List<ItemReq> itens, List<PagReq> pagamentos) {}

    @POST @Transactional
    public Venda finalizar(VendaReq req) {
        Venda v = new Venda(); v.usuario = auditor.usuario();
        // CPF opcional (venda anônima): se informado, os dígitos verificadores precisam conferir ANTES de qualquer baixa de estoque ou pagamento
        try { v.cpfCliente = Cpf.normalizarOuNulo(req.cpf()); } catch (IllegalArgumentException ex) { throw Http.erro(422, "CPF inválido"); } List<String> problemasNcm = new ArrayList<>();
        for (ItemReq ir : req.itens()) {
            ProdutoDTO p = catalogo.buscar(ir.produtoId());
            if (Boolean.FALSE.equals(p.ativo())) throw new WebApplicationException("Produto desativado: " + p.nome(), 422);
            { String pn = p.problemaNcm(); if (pn != null) problemasNcm.add(p.nome() + " (" + pn + ")"); }              // preço SEMPRE vem do servidor
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
        BigDecimal caderno_ = null;
        BigDecimal pago = BigDecimal.ZERO; List<TefTransacao> tefs = new ArrayList<>(); List<PixCobranca> pixs = new ArrayList<>();
        for (PagReq pr : req.pagamentos()) {
            Pagamento pg = new Pagamento(); pg.venda = v; pg.meio = pr.meio(); pg.valor = pr.valor();
            if ("CADERNO".equals(pr.meio())) {                          // fiado: só para cliente cadastrado (CPF) e dentro do limite
                if (caderno_ != null) throw Http.erro(422, "Use um único pagamento CADERNO por venda");
                if (v.cpfCliente == null) throw Http.erro(422, "Para anotar no caderno informe o CPF do cliente");
                caderno_ = pr.valor();
                try {
                    var sit = caderno.situacao(v.cpfCliente, 1);
                    if (pr.valor().compareTo(sit.disponivel()) > 0)
                        throw Http.erro(422, "Limite do caderno excedido: " + sit.nome() + " deve R$ " + sit.saldo() + " e pode dever até R$ " + sit.limite());
                } catch (jakarta.ws.rs.ProcessingException ex) { throw Http.erro(503, "Caderno indisponível no momento (retaguarda fora do ar)");
                } catch (WebApplicationException ex) {
                    if (ex.getResponse().getStatus() == 404) throw Http.erro(422, "Cliente não cadastrado: cadastre o CPF em Clientes para usar o caderno");
                    throw ex;
                }
            }
            if (Cartoes.beneficio(pr.meio())) {                        // vale: a operadora é obrigatória e precisa aceitar o saldo
                if (!Cartoes.aceita(pr.operadora(), pr.meio())) throw Http.erro(422, "Informe uma operadora válida para " + pr.meio() + " (ex.: ALELO_ALIMENTACAO, TICKET, PLUXEE, VR)");
            } else if (pr.operadora() != null && !pr.operadora().isBlank() && !Cartoes.aceita(pr.operadora(), pr.meio()))
                throw Http.erro(422, "A operadora " + pr.operadora() + " não é aceita no meio " + pr.meio());
            if (pr.operadora() != null && !pr.operadora().isBlank()) pg.operadora = pr.operadora();
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
        if (!problemasNcm.isEmpty() && (ncmObrigatorio || (ncmObrigatorioTef && !tefs.isEmpty())))
            throw Http.erro(422, "A NFC-e exige NCM válido e vigente em todos os itens. Corrija em Produtos e preços: " + String.join("; ", problemasNcm));
        v.status = Venda.Status.PAGA; v.persist();
        tefs.forEach(t -> { t.vendaId = v.id; t.atualizadoEm = LocalDateTime.now(); });   // vincula o cartão à venda
        pixs.forEach(px -> px.vendaId = v.id);                                              // vincula o Pix à venda
        if (caderno_ != null) {                                  // anota a dívida; se falhar a venda inteira volta atrás (transação)
            try { caderno.lancar(new Clients.CadernoLanc(v.cpfCliente, caderno_, v.id, "Venda #" + v.id, v.usuario)); }
            catch (jakarta.ws.rs.ProcessingException ex) { throw Http.erro(503, "Caderno indisponível (retaguarda fora do ar); venda não finalizada"); }
            catch (WebApplicationException ex) { throw Http.erro(ex.getResponse().getStatus() >= 500 ? 503 : 422, "Não foi possível anotar no caderno (" + ex.getMessage() + "); venda não finalizada"); }
        }
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade, "VENDA", "venda " + v.id)));
        // auditoria da venda (best-effort: se a auditoria estiver fora do ar, a venda não é bloqueada)
        StringBuilder meios = new StringBuilder();
        for (Pagamento pg : v.pagamentos) meios.append(meios.length() == 0 ? "" : " + ").append(pg.meio).append(" R$ ").append(pg.valor);
        String cpfMask = v.cpfCliente == null ? "sem CPF" : "CPF ***" + v.cpfCliente.substring(Math.max(0, v.cpfCliente.length() - 4));
        auditor.registrar("VENDA", "Venda", v.id, "Venda #" + v.id + " finalizada: " + v.itens.size() + " item(ns), total R$ " + v.total
            + (v.desconto.signum() > 0 ? " (desconto R$ " + v.desconto + ")" : "") + " | " + meios + " | " + cpfMask, null, null);
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
        try { caderno.estornar(id); }                              // venda fiada: a dívida sai do caderno (sem dívida = nada a fazer)
        catch (WebApplicationException | jakarta.ws.rs.ProcessingException ex) { throw Http.erro(503, "Não foi possível estornar a dívida do caderno; venda não cancelada"); }
        v.itens.forEach(i -> estoque.baixa(new BaixaDTO(i.produtoId, i.quantidade.negate(), "AJUSTE", "estorno venda " + id)));
        auditor.registrar("CANCELAMENTO", "Venda", id, "Venda #" + id + " cancelada (total R$ " + v.total + ")", null, null);
        return v;
    }
    public record VendaResumo(Long id, LocalDateTime data, BigDecimal total, String status, String cpf) {}
    /** Valida o CPF do consumidor (Nota Fiscal Gaúcha): dígitos verificadores, sem repetidos. */
    @GET @Path("/cpf/{cpf}/validar")
    public Map<String, Object> validarCpf(@PathParam("cpf") String cpf) {
        boolean ok = Cpf.valido(cpf); return Map.of("valido", ok, "cpf", Cpf.somenteDigitos(cpf), "formatado", ok ? Cpf.formatar(cpf) : "");
    }
    @GET @Path("/{id}") @Transactional
    public Venda buscar(@PathParam("id") Long id) { Venda v = Venda.findById(id); if (v == null) throw new NotFoundException(); return v; }

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
    /** Por cliente (CPF): quantas compras, quanto gastou, ticket médio e última compra. Só vendas PAGAS com CPF; período opcional (AAAA-MM-DD). */
    public record ClienteCompras(String cpf, long compras, BigDecimal total, BigDecimal ticketMedio, LocalDateTime ultimaCompra, BigDecimal noCaderno) {}
    @GET @Path("/relatorio/clientes") @Transactional
    public List<ClienteCompras> relatorioClientes(@QueryParam("de") String de, @QueryParam("ate") String ate) {
        LocalDateTime ini = de == null || de.isBlank() ? LocalDateTime.of(2000, 1, 1, 0, 0) : java.time.LocalDate.parse(de).atStartOfDay();
        LocalDateTime fim = ate == null || ate.isBlank() ? LocalDateTime.now().plusDays(1) : java.time.LocalDate.parse(ate).plusDays(1).atStartOfDay();
        List<Object[]> l = Venda.getEntityManager().createQuery(
            "select v.cpfCliente, count(v), sum(v.total), max(v.data) from Venda v where v.status = :s and v.cpfCliente is not null and v.data >= :i and v.data < :f group by v.cpfCliente order by sum(v.total) desc", Object[].class)
            .setParameter("s", Venda.Status.PAGA).setParameter("i", ini).setParameter("f", fim).getResultList();
        Map<String, BigDecimal> fiado = new HashMap<>();
        for (Object[] r : Venda.getEntityManager().createQuery(
            "select v.cpfCliente, sum(p.valor) from PagamentoVenda p join p.venda v where p.meio = 'CADERNO' and v.status = :s and v.cpfCliente is not null and v.data >= :i and v.data < :f group by v.cpfCliente", Object[].class)
            .setParameter("s", Venda.Status.PAGA).setParameter("i", ini).setParameter("f", fim).getResultList()) fiado.put((String) r[0], (BigDecimal) r[1]);
        return l.stream().map(r -> {
            long n = ((Number) r[1]).longValue(); BigDecimal t = ((BigDecimal) r[2]).setScale(2, RoundingMode.HALF_UP);
            return new ClienteCompras((String) r[0], n, t, t.divide(BigDecimal.valueOf(Math.max(1, n)), 2, RoundingMode.HALF_UP), (LocalDateTime) r[3], fiado.getOrDefault((String) r[0], BigDecimal.ZERO));
        }).toList();
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
}
