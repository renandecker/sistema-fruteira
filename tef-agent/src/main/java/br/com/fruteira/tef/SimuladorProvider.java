package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Simulador de TEF para desenvolvimento e ensaio de homologação. NÃO movimenta dinheiro.
 * Os CENTAVOS do valor escolhem o resultado:
 *   ,01 negada (saldo insuficiente) · ,02 negada (senha incorreta) · ,03 erro de comunicação · ,04 cancelada no pinpad · demais: aprovada
 */
@ApplicationScoped
public class SimuladorProvider implements TefProvider {
    private static final Logger LOG = Logger.getLogger(SimuladorProvider.class);
    private static final String[] BANDEIRAS = {"VISA", "MASTERCARD", "ELO", "HIPERCARD"};
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    @ConfigProperty(name = "tef.simulador.latencia-ms", defaultValue = "1200") long latencia;
    @ConfigProperty(name = "tef.simulador.cnpj", defaultValue = "00000000000191") String cnpj;
    private final AtomicInteger sequencia = new AtomicInteger(1000);

    @Override public String nome() { return "simulador"; }
    @Override public boolean pinpadConectado() { return true; }

    @Override
    public Resultado executar(Pedido p, Consumer<Progresso> progresso, BooleanSupplier cancelado) {
        progresso.accept(new Progresso("AGUARDANDO_CARTAO", "Insira, aproxime ou passe o cartão"));
        if (!esperar(latencia, cancelado)) return cancelada("Operação cancelada pelo operador");
        progresso.accept(new Progresso("AGUARDANDO_SENHA", "Digite a senha no pinpad"));
        if (!esperar(latencia, cancelado)) return cancelada("Operação cancelada pelo operador");
        progresso.accept(new Progresso("PROCESSANDO", "Processando… não retire o cartão"));
        if (!esperar(latencia / 2, cancelado)) return cancelada("Operação cancelada pelo operador");

        int centavos = (int) (p.valor().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue() % 100);
        return switch (centavos) {
            case 1 -> negada("Transação negada: saldo insuficiente");
            case 2 -> negada("Transação negada: senha incorreta");
            case 3 -> new Resultado("ERRO", "Falha de comunicação com a adquirente", null, null, null, null, null, null, null, null, null);
            case 4 -> cancelada("Operação cancelada no pinpad");
            default -> aprovada(p);
        };
    }

    private Resultado aprovada(Pedido p) {
        int n = sequencia.incrementAndGet();
        String nsu = String.format("%06d", n), aut = String.format("%06d", (n * 7919) % 1_000_000), bandeira = BANDEIRAS[n % BANDEIRAS.length];
        String cartao = "**** **** **** " + String.format("%04d", (n * 31) % 10_000);
        String tipo = switch (p.tipo()) { case "DEBITO" -> "DÉBITO"; case "CREDITO_PARCELADO" -> "CRÉDITO PARCELADO " + p.parcelas() + "x"; default -> "CRÉDITO À VISTA"; };
        String corpo = comprovanteBase("VENDA", tipo, bandeira, cartao, p.valor(), nsu, aut);
        return new Resultado("APROVADA", "Transação aprovada", nsu, nsu, aut, bandeira, "SIMULADOR", cnpj, cartao,
            corpo + "\n        VIA CLIENTE\n", corpo + "\n   ____________________________\n   ASSINATURA DO CLIENTE\n        VIA ESTABELECIMENTO\n");
    }
    private String comprovanteBase(String titulo, String tipo, String bandeira, String cartao, BigDecimal valor, String nsu, String aut) {
        return "      FRUTEIRA CONVENTOS\n  *** TRANSAÇÃO SIMULADA ***\n   *** SEM VALOR FISCAL ***\n\n"
            + "   " + titulo + " " + tipo + "\n   " + bandeira + "  " + cartao + "\n"
            + "   VALOR  R$ " + valor.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "\n"
            + "   NSU " + nsu + "   AUT " + aut + "\n   " + LocalDateTime.now().format(HORA) + "\n";
    }
    private Resultado negada(String msg) { return new Resultado("NEGADA", msg, null, null, null, null, null, null, null, null, null); }
    private Resultado cancelada(String msg) { return new Resultado("CANCELADA", msg, null, null, null, null, null, null, null, null, null); }

    /** Espera em fatias de 100 ms; devolve false se cancelaram. */
    private boolean esperar(long ms, BooleanSupplier cancelado) {
        long fim = System.currentTimeMillis() + Math.max(ms, 0);
        try {
            while (System.currentTimeMillis() < fim) { if (cancelado.getAsBoolean()) return false; Thread.sleep(100); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return !cancelado.getAsBoolean();
    }

    @Override public void confirmar(String requisicao) { LOG.infof("[simulador] transação %s CONFIRMADA", requisicao); }
    @Override public void desfazer(String requisicao) { LOG.infof("[simulador] transação %s DESFEITA", requisicao); }
    @Override
    public Resultado estornar(Estorno e) {
        int n = sequencia.incrementAndGet(); String nsu = String.format("%06d", n);
        String corpo = comprovanteBase("CANCELAMENTO", "(NSU original " + e.nsu() + ")", "", "", e.valor(), nsu, "------");
        return new Resultado("APROVADA", "Cancelamento aprovado", nsu, nsu, null, null, "SIMULADOR", cnpj, null, corpo + "\n        VIA CLIENTE\n", corpo + "\n        VIA ESTABELECIMENTO\n");
    }
}
