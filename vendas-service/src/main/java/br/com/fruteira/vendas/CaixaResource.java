package br.com.fruteira.vendas;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turno de caixa: abertura (fundo de troco), SANGRIA e SUPRIMENTO, e fechamento CEGO.
 * Cego: o operador informa o que CONTOU e nunca vê o saldo do sistema; a conferência é só do gerente.
 * O saldo esperado da gaveta = fundo + vendas em dinheiro do operador + suprimentos − sangrias.
 */
@Path("/caixa") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class CaixaResource {
    private static final BigDecimal MAX = new BigDecimal("100000");
    @Inject Auditor auditor;
    /** A partir deste saldo o PDV avisa (sem mostrar o valor) que é hora de fazer uma sangria. */
    @ConfigProperty(name = "fruteira.caixa.limite-gaveta", defaultValue = "500") BigDecimal limiteGaveta;

    /** PIN do supervisor (opcional: sem ele só vale o PIN do gerente) e do gerente, que também pode aprovar. */
    @ConfigProperty(name = "fruteira.supervisor.pin") java.util.Optional<String> pinSupervisorCfg;
    @ConfigProperty(name = "fruteira.gerente.pin") String pinGerente;

    public record Abertura(BigDecimal fundoTroco) {}
    public record Configuracao(Boolean sangriaExigeAprovacao) {}
    public record Mov(BigDecimal valor, String motivo) {}
    public record Fechamento(BigDecimal contadoDinheiro) {}

    static BigDecimal c2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal valorValido(BigDecimal v, boolean aceitaZero) {
        if (v == null || v.signum() < 0 || (!aceitaZero && v.signum() == 0)) throw Http.erro(422, aceitaZero ? "Informe o valor (zero ou mais)" : "Informe um valor maior que zero");
        if (v.compareTo(MAX) > 0) throw Http.erro(422, "Valor acima do limite permitido");
        return c2(v);
    }
    static TurnoCaixa aberto(String operador) { return TurnoCaixa.find("operador = ?1 and status = 'ABERTO'", operador).firstResult(); }

    /** Saldo que o SISTEMA espera na gaveta (nunca é enviado ao operador). */
    BigDecimal esperado(TurnoCaixa t) {
        LocalDateTime ate = t.fechamento != null ? t.fechamento : LocalDateTime.now().plusMinutes(1);
        BigDecimal vendas = Venda.getEntityManager().createQuery(
            "select coalesce(sum(p.valor),0) from PagamentoVenda p where p.meio='DINHEIRO' and p.venda.status = :s and p.venda.usuario = :u and p.venda.data >= :ini and p.venda.data <= :fim", BigDecimal.class)
            .setParameter("s", Venda.Status.PAGA).setParameter("u", t.operador).setParameter("ini", t.abertura).setParameter("fim", ate).getSingleResult();
        BigDecimal sup = soma(t.id, "SUPRIMENTO"), san = soma(t.id, "SANGRIA");
        return c2(t.fundoTroco.add(vendas).add(sup).subtract(san));
    }
    private BigDecimal soma(Long turnoId, String tipo) {
        return MovimentoCaixa.getEntityManager().createQuery("select coalesce(sum(m.valor),0) from MovimentoCaixa m where m.turnoId = :t and m.tipo = :tp", BigDecimal.class)
            .setParameter("t", turnoId).setParameter("tp", tipo).getSingleResult();
    }

    /** Turno do operador logado, SEM saldos: só o aviso de que a gaveta já pede sangria. */
    @GET @Path("/turno") @Transactional
    public Map<String, Object> meuTurno() {
        TurnoCaixa t = aberto(auditor.usuario());
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) { m.put("aberto", false); return m; }
        List<MovimentoCaixa> movs = MovimentoCaixa.list("turnoId = ?1 order by data desc", t.id);
        m.put("aberto", true); m.put("id", t.id); m.put("abertura", t.abertura);
        m.put("sangriaRecomendada", esperado(t).compareTo(limiteGaveta) >= 0);
        m.put("movimentos", movs.stream().map(x -> Map.of("id", x.id, "tipo", x.tipo, "valor", x.valor, "motivo", x.motivo == null ? "" : x.motivo, "data", x.data)).toList());
        return m;
    }

    @POST @Path("/abrir") @Transactional
    public TurnoCaixa abrir(Abertura a) {
        String op = auditor.usuario();
        if (aberto(op) != null) throw Http.erro(409, "Você já tem um turno aberto");
        TurnoCaixa t = new TurnoCaixa(); t.operador = op; t.fundoTroco = valorValido(a == null ? null : a.fundoTroco(), true); t.persist();
        auditor.registrar("CADASTRO", "Abertura de caixa", t.id, "Fundo de troco R$ " + t.fundoTroco, null, null);
        return t;
    }

    private static boolean pinConfere(String esperado, String informado) {
        return esperado != null && !esperado.isEmpty() && informado != null
            && java.security.MessageDigest.isEqual(esperado.getBytes(java.nio.charset.StandardCharsets.UTF_8), informado.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Configuração do caixa para a tela: se a sangria do usuário atual exige aprovação e se ele pode mudar a configuração (supervisor ou gerente). */
    @GET @Path("/configuracao") @Transactional
    public Map<String, Object> configuracao() {
        ConfigCaixa c = ConfigCaixa.atual(); boolean sup = auditor.supervisorOuMais();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sangriaExigeAprovacao", c.sangriaExigeAprovacao);
        m.put("precisaAprovacao", c.sangriaExigeAprovacao && !sup);       // supervisor e gerente aprovam a si mesmos
        m.put("podeConfigurar", sup);
        m.put("atualizadoPor", c.atualizadoPor); m.put("atualizadoEm", c.atualizadoEm);
        return m;
    }

    /** Só o supervisor (ou gerente) liga/desliga a aprovação do supervisor na sangria. Desligada por padrão. */
    @PUT @Path("/configuracao") @Transactional
    public Map<String, Object> configurar(Configuracao in) {
        if (!auditor.supervisorOuMais()) throw Http.erro(403, "Somente o supervisor ou o gerente altera esta configuração");
        if (in == null || in.sangriaExigeAprovacao() == null) throw Http.erro(422, "Informe sangriaExigeAprovacao (true ou false)");
        ConfigCaixa c = ConfigCaixa.<ConfigCaixa>findAll().firstResult(); boolean antes = c != null && c.sangriaExigeAprovacao;
        if (c == null) c = new ConfigCaixa();
        c.sangriaExigeAprovacao = in.sangriaExigeAprovacao(); c.atualizadoPor = auditor.usuario(); c.atualizadoEm = LocalDateTime.now(); c.persist();
        auditor.registrar("EDICAO", "Configuração do caixa", c.id, "Aprovação do supervisor na sangria: " + (antes ? "ligada" : "desligada") + " → " + (c.sangriaExigeAprovacao ? "LIGADA" : "desligada"), antes, c.sangriaExigeAprovacao);
        return configuracao();
    }

    @POST @Path("/sangria") @Transactional
    public Map<String, Object> sangria(Mov in, @HeaderParam("X-Supervisor-Pin") String pin) {
        TurnoCaixa t = aberto(auditor.usuario());
        if (t == null) throw Http.erro(409, "Abra o turno antes de fazer sangria");
        BigDecimal v = valorValido(in == null ? null : in.valor(), false);
        String aprovacao = null;
        if (ConfigCaixa.atual().sangriaExigeAprovacao && !auditor.supervisorOuMais()) {          // aprovação ligada: o operador precisa do PIN do supervisor
            if (pin == null || pin.isBlank()) throw Http.erro(403, "Sangria requer a aprovação do supervisor (PIN)");
            if (!pinConfere(pinSupervisorCfg.orElse(""), pin) && !pinConfere(pinGerente, pin)) {
                auditor.registrar("SEGURANCA", "Sangria de caixa", t.id, "PIN de aprovação INCORRETO ao tentar sangria de R$ " + v + " (turno " + t.id + ")", null, null);
                throw Http.erro(403, "PIN do supervisor incorreto");
            }
            aprovacao = pinConfere(pinSupervisorCfg.orElse(""), pin) ? "supervisor (PIN)" : "gerente (PIN)";
        }
        MovimentoCaixa m = novo(t, "SANGRIA", v, in.motivo());
        m.aprovacao = aprovacao;
        m.acimaDoSaldo = v.compareTo(esperado(t)) > 0;            // conferência cega: só vai para a auditoria
        m.persist();
        auditor.registrar("CADASTRO", "Sangria de caixa", m.id, "Sangria R$ " + v + " (turno " + t.id + ")" + (m.acimaDoSaldo ? " — ACIMA do saldo do sistema" : "") + (aprovacao == null ? "" : " — aprovada pelo " + aprovacao) + (m.motivo == null ? "" : " | " + m.motivo), null, null);
        return Map.of("id", m.id, "valor", v, "data", m.data, "operador", m.operador,
            "comprovante", "      FRUTEIRA CONVENTOS\n        SANGRIA DE CAIXA\n"
              + "Nº " + m.id + "   " + m.data.toString().replace('T', ' ').substring(0, 16) + "\n"
              + "Operador: " + m.operador + "\nValor retirado: R$ " + v + "\n" + (m.motivo == null ? "" : "Motivo: " + m.motivo + "\n") + (aprovacao == null ? "" : "Aprovada pelo " + aprovacao + "\n")
              + "\n______________________\nAssinatura / conferente\n");
    }

    @POST @Path("/suprimento") @Transactional
    public Map<String, Object> suprimento(Mov in) {
        TurnoCaixa t = aberto(auditor.usuario());
        if (t == null) throw Http.erro(409, "Abra o turno antes de lançar suprimento");
        BigDecimal v = valorValido(in == null ? null : in.valor(), false);
        MovimentoCaixa m = novo(t, "SUPRIMENTO", v, in.motivo()); m.persist();
        auditor.registrar("CADASTRO", "Suprimento de caixa", m.id, "Suprimento R$ " + v + " (turno " + t.id + ")", null, null);
        return Map.of("id", m.id, "valor", v);
    }
    private static MovimentoCaixa novo(TurnoCaixa t, String tipo, BigDecimal v, String motivo) {
        MovimentoCaixa m = new MovimentoCaixa(); m.turnoId = t.id; m.tipo = tipo; m.valor = v; m.operador = t.operador;
        m.motivo = motivo == null || motivo.isBlank() ? null : motivo.trim().substring(0, Math.min(200, motivo.trim().length()));
        return m;
    }

    /** Fechamento cego: grava contado x esperado; o operador recebe só a confirmação. */
    @POST @Path("/fechamento") @Transactional
    public Map<String, Object> fechar(Fechamento f) {
        TurnoCaixa t = aberto(auditor.usuario());
        if (t == null) throw Http.erro(409, "Nenhum turno aberto para fechar");
        t.contadoFechamento = valorValido(f == null ? null : f.contadoDinheiro(), true);
        t.fechamento = LocalDateTime.now();
        t.esperadoFechamento = esperado(t); t.diferenca = c2(t.contadoFechamento.subtract(t.esperadoFechamento)); t.status = "FECHADO";
        auditor.registrar("CADASTRO", "Fechamento de caixa", t.id, "Contado R$ " + t.contadoFechamento + " | esperado R$ " + t.esperadoFechamento + " | diferença R$ " + t.diferenca, null, null);
        return Map.of("registrado", true);
    }

    // ---------------- gerente ----------------
    private void soGerente() { if (!auditor.gerente()) throw Http.erro(403, "Somente o gerente vê os saldos do caixa"); }

    @GET @Path("/turnos") @Transactional
    public List<Map<String, Object>> turnos() {
        soGerente();
        return TurnoCaixa.<TurnoCaixa>find("order by abertura desc").page(0, 60).list().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id); m.put("operador", t.operador); m.put("status", t.status); m.put("abertura", t.abertura); m.put("fechamento", t.fechamento);
            m.put("fundoTroco", t.fundoTroco); m.put("sangrias", soma(t.id, "SANGRIA")); m.put("suprimentos", soma(t.id, "SUPRIMENTO"));
            boolean aberto = "ABERTO".equals(t.status);
            m.put("esperado", aberto ? esperado(t) : t.esperadoFechamento); m.put("contado", t.contadoFechamento);
            m.put("diferenca", t.diferenca);
            m.put("sangriasAcimaDoSaldo", MovimentoCaixa.count("turnoId = ?1 and tipo = 'SANGRIA' and acimaDoSaldo = true", t.id));
            return m;
        }).toList();
    }

    @GET @Path("/turnos/{id}/movimentos") @Transactional
    public List<MovimentoCaixa> movimentos(@PathParam("id") Long id) {
        soGerente();
        return MovimentoCaixa.list("turnoId = ?1 order by data", id);
    }
}
