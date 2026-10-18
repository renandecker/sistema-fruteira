package br.com.fruteira.retaguarda;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conta do caderno, sem banco (testada por testar-caderno.sh). Os pagamentos/perdões abatem as dívidas MAIS ANTIGAS primeiro (FIFO),
 * então "desde" é a data da dívida mais velha que ainda não foi paga por inteiro, e o atraso conta a partir dela.
 */
public final class SaldoCaderno {
    private SaldoCaderno() {}
    public record Lanc(String tipo, BigDecimal valor, LocalDateTime data) {}
    public record Resumo(BigDecimal saldo, BigDecimal fiado, BigDecimal pago, LocalDateTime desde, long diasAtraso) {}

    static BigDecimal c2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    /** Só passe os lançamentos NÃO cancelados. */
    public static Resumo calcular(List<Lanc> lancs, LocalDate hoje) {
        BigDecimal fiado = BigDecimal.ZERO, abatido = BigDecimal.ZERO;
        List<Lanc> dividas = new ArrayList<>();
        for (Lanc l : lancs) {
            if (l.valor() == null || l.tipo() == null) continue;
            if (l.tipo().equals("DIVIDA")) { fiado = fiado.add(l.valor()); dividas.add(l); }
            else if (l.tipo().equals("PAGAMENTO") || l.tipo().equals("PERDAO")) abatido = abatido.add(l.valor());
        }
        dividas.sort(Comparator.comparing(Lanc::data));
        BigDecimal restante = abatido;
        LocalDateTime desde = null;
        for (Lanc d : dividas) {
            if (restante.compareTo(d.valor()) >= 0) { restante = restante.subtract(d.valor()); continue; }
            desde = d.data(); break;                       // primeira dívida ainda (parcialmente) aberta
        }
        BigDecimal saldo = c2(fiado.subtract(abatido).max(BigDecimal.ZERO));
        if (saldo.signum() == 0) desde = null;
        long dias = desde == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(desde.toLocalDate(), hoje));
        return new Resumo(saldo, c2(fiado), c2(abatido), desde, dias);
    }
}
