package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Cliente;
import br.com.fruteira.retaguarda.SaldoCaderno.Lanc;
import br.com.fruteira.retaguarda.SaldoCaderno.Resumo;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Caderno (fiado). O cliente PRECISA estar cadastrado (CPF): é com ele que o dono cobra depois.
 * Nada é apagado: erro = cancelar o lançamento (gerente) e a trilha de auditoria guarda tudo.
 */
@Path("/caderno") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class CadernoResource {
    private static final BigDecimal MAX = new BigDecimal("100000");
    @Inject Auditor auditor;
    /** Teto de dívida de quem não tem limite próprio (R$). */
    @ConfigProperty(name = "fruteira.caderno.limite-padrao", defaultValue = "300") BigDecimal limitePadrao;

    public record Lancar(String cpf, BigDecimal valor, Long vendaId, String obs, String usuario) {}
    public record Receber(String cpf, BigDecimal valor, String obs) {}

    private static WebApplicationException erro(int s, String m) {
        return new WebApplicationException(Response.status(s).entity(m).type(MediaType.TEXT_PLAIN).build());
    }
    private static BigDecimal valor(BigDecimal v) {
        if (v == null || v.signum() <= 0) throw erro(422, "Informe um valor maior que zero");
        if (v.compareTo(MAX) > 0) throw erro(422, "Valor acima do limite permitido");
        return v.setScale(2, RoundingMode.HALF_UP);
    }
    private static String cpfLimpo(String cpf) { return cpf == null ? "" : cpf.replaceAll("\\D", ""); }
    private static Cliente cliente(String cpf) {
        Cliente c = Cliente.find("cpf", cpfLimpo(cpf)).firstResult();
        if (c == null || Boolean.FALSE.equals(c.ativo)) throw erro(404, "Cliente não cadastrado: o caderno só vale para cliente com cadastro");
        return c;
    }
    private BigDecimal limiteDe(Cliente c) { return c.limiteCaderno != null ? c.limiteCaderno : limitePadrao; }

    private static List<CadernoLancamento> lancamentos(String cpf) { return CadernoLancamento.list("cpf = ?1 order by data, id", cpf); }
    private static Resumo resumo(List<CadernoLancamento> ls) {
        return SaldoCaderno.calcular(ls.stream().filter(l -> !Boolean.TRUE.equals(l.cancelado)).map(l -> new Lanc(l.tipo, l.valor, l.data)).toList(), LocalDate.now());
    }
    private Map<String, Object> visao(Cliente c, List<CadernoLancamento> ls) {
        Resumo r = resumo(ls);
        BigDecimal limite = limiteDe(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpf", c.cpf); m.put("nome", c.nome); m.put("telefone", c.telefone);
        m.put("saldo", r.saldo()); m.put("fiado", r.fiado()); m.put("pago", r.pago());
        m.put("desde", r.desde()); m.put("diasAtraso", r.diasAtraso());
        m.put("limite", limite); m.put("disponivel", limite.subtract(r.saldo()).max(BigDecimal.ZERO));
        m.put("devendo", r.saldo().signum() > 0);
        return m;
    }

    /** Situação de um cliente (o PDV usa no alerta) + os lançamentos, do mais novo para o mais velho. */
    @GET @Path("/cliente/{cpf}")
    public Map<String, Object> cliente_(@PathParam("cpf") String cpf, @QueryParam("limite") @DefaultValue("30") int limite) {
        Cliente c = cliente(cpf);
        List<CadernoLancamento> ls = lancamentos(c.cpf);
        Map<String, Object> m = visao(c, ls);
        List<CadernoLancamento> rec = new ArrayList<>(ls); Collections.reverse(rec);
        m.put("lancamentos", rec.stream().limit(Math.max(1, Math.min(limite, 500))).toList());
        return m;
    }

    /** Quem está devendo (saldo > 0), do maior para o menor, e os totais. */
    @GET @Path("/devedores")
    public Map<String, Object> devedores() {
        Map<String, List<CadernoLancamento>> porCpf = CadernoLancamento.<CadernoLancamento>listAll().stream().collect(Collectors.groupingBy(l -> l.cpf));
        List<Map<String, Object>> lista = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO; int atrasados = 0;
        for (var e : porCpf.entrySet()) {
            Cliente c = Cliente.find("cpf", e.getKey()).firstResult();
            if (c == null) continue;
            Map<String, Object> v = visao(c, e.getValue());
            if (!(Boolean) v.get("devendo")) continue;
            lista.add(v); total = total.add((BigDecimal) v.get("saldo"));
            if ((Long) v.get("diasAtraso") >= 30) atrasados++;
        }
        lista.sort((a, b) -> ((BigDecimal) b.get("saldo")).compareTo((BigDecimal) a.get("saldo")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAReceber", total.setScale(2, RoundingMode.HALF_UP)); m.put("devedores", lista.size()); m.put("atrasados30", atrasados);
        m.put("maiorDevedor", lista.isEmpty() ? null : lista.get(0)); m.put("lista", lista);
        return m;
    }

    /** Chamado pelo vendas-service ao fechar uma venda no caderno. Repetir o mesmo vendaId não duplica a dívida. */
    @POST @Path("/lancar") @Transactional
    public Map<String, Object> lancar(Lancar in) {
        if (in == null) throw erro(422, "Dados ausentes");
        BigDecimal v = valor(in.valor());
        Cliente c = cliente(in.cpf());
        if (in.vendaId() != null) {
            CadernoLancamento ja = CadernoLancamento.find("vendaId = ?1 and tipo = 'DIVIDA' and cancelado = false", in.vendaId()).firstResult();
            if (ja != null) return visao(c, lancamentos(c.cpf));
        }
        List<CadernoLancamento> ls = lancamentos(c.cpf);
        BigDecimal limite = limiteDe(c), saldo = resumo(ls).saldo();
        if (saldo.add(v).compareTo(limite) > 0)
            throw erro(422, "Limite do caderno excedido: " + c.nome + " deve R$ " + saldo + " e o limite é R$ " + limite.setScale(2, RoundingMode.HALF_UP));
        CadernoLancamento l = novo(c.cpf, "DIVIDA", v, in.obs(), in.usuario());
        l.vendaId = in.vendaId(); l.persist();
        auditor.registrar("CADASTRO", "Caderno", l.id, "Fiado de R$ " + v + " para " + c.nome + (in.vendaId() == null ? "" : " (venda " + in.vendaId() + ")"), null, null);
        ls.add(l);
        return visao(c, ls);
    }

    /** O cliente pagou (parte da) dívida no caixa. Não aceita pagar mais do que deve. */
    @POST @Path("/pagamento") @Transactional
    public Map<String, Object> pagamento(Receber in) {
        if (in == null) throw erro(422, "Dados ausentes");
        BigDecimal v = valor(in.valor());
        Cliente c = cliente(in.cpf());
        List<CadernoLancamento> ls = lancamentos(c.cpf);
        BigDecimal saldo = resumo(ls).saldo();
        if (saldo.signum() == 0) throw erro(409, c.nome + " não tem dívida no caderno");
        if (v.compareTo(saldo) > 0) throw erro(422, "O cliente deve R$ " + saldo + "; não dá para receber R$ " + v);
        CadernoLancamento l = novo(c.cpf, "PAGAMENTO", v, in.obs(), null); l.persist();
        auditor.registrar("BAIXA", "Caderno", l.id, "Recebido R$ " + v + " de " + c.nome + " (saldo antes R$ " + saldo + ")", null, null);
        ls.add(l);
        return visao(c, ls);
    }

    /** Perdão da dívida (só gerente): baixa sem receber. */
    @POST @Path("/perdao") @Transactional
    public Map<String, Object> perdao(Receber in) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode perdoar dívida");
        if (in == null) throw erro(422, "Dados ausentes");
        BigDecimal v = valor(in.valor());
        Cliente c = cliente(in.cpf());
        List<CadernoLancamento> ls = lancamentos(c.cpf);
        BigDecimal saldo = resumo(ls).saldo();
        if (v.compareTo(saldo) > 0) throw erro(422, "O cliente deve R$ " + saldo + "; não dá para perdoar R$ " + v);
        if (in.obs() == null || in.obs().isBlank()) throw erro(422, "Informe o motivo do perdão");
        CadernoLancamento l = novo(c.cpf, "PERDAO", v, in.obs(), null); l.persist();
        auditor.registrar("BAIXA", "Caderno", l.id, "PERDÃO de R$ " + v + " a " + c.nome + " — " + in.obs(), null, null);
        ls.add(l);
        return visao(c, ls);
    }

    /** Cancela um lançamento lançado por engano (só gerente). Lançamento ligado a venda: cancele a venda. */
    @POST @Path("/{id}/cancelar") @Transactional
    public Map<String, Object> cancelar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode cancelar lançamento");
        CadernoLancamento l = CadernoLancamento.findById(id);
        if (l == null) throw new NotFoundException();
        if (Boolean.TRUE.equals(l.cancelado)) throw erro(409, "Lançamento já cancelado");
        if (l.vendaId != null) throw erro(409, "Dívida ligada à venda " + l.vendaId + ": cancele a venda no PDV");
        l.cancelado = true;
        auditor.registrar("CANCELAMENTO", "Caderno", l.id, "Lançamento " + l.tipo + " de R$ " + l.valor + " cancelado", null, null);
        Cliente c = cliente(l.cpf);
        return visao(c, lancamentos(c.cpf));
    }

    /** Chamado pelo vendas-service quando a venda é cancelada: a dívida some do caderno. Sem dívida = nada a fazer. */
    @POST @Path("/venda/{vendaId}/estornar") @Transactional
    public Map<String, Object> estornar(@PathParam("vendaId") Long vendaId) {
        CadernoLancamento l = CadernoLancamento.find("vendaId = ?1 and tipo = 'DIVIDA' and cancelado = false", vendaId).firstResult();
        if (l == null) return Map.of("estornado", false);
        l.cancelado = true;
        auditor.registrar("CANCELAMENTO", "Caderno", l.id, "Fiado de R$ " + l.valor + " estornado (venda " + vendaId + " cancelada)", null, null);
        return Map.of("estornado", true, "valor", l.valor);
    }

    private CadernoLancamento novo(String cpf, String tipo, BigDecimal v, String obs, String usuario) {
        CadernoLancamento l = new CadernoLancamento();
        l.cpf = cpf; l.tipo = tipo; l.valor = v; l.cancelado = false;
        l.usuario = usuario != null && !usuario.isBlank() ? usuario : auditor.quem().usuario();
        l.obs = obs == null || obs.isBlank() ? null : obs.trim().substring(0, Math.min(200, obs.trim().length()));
        return l;
    }
}
