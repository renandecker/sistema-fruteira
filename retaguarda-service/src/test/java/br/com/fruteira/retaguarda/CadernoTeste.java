package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.SaldoCaderno.Lanc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Testes do saldo do caderno só com o JDK: ./testar-caderno.sh */
public class CadernoTeste {
    static int ok, falhas;
    static void ck(String nome, boolean cond) { if (cond) ok++; else { falhas++; System.out.println("FALHOU: " + nome); } }
    static BigDecimal b(String s) { return new BigDecimal(s); }
    static LocalDateTime d(String dia) { return LocalDate.parse(dia).atTime(10, 0); }
    static Lanc div(String v, String dia) { return new Lanc("DIVIDA", b(v), d(dia)); }
    static Lanc pag(String v, String dia) { return new Lanc("PAGAMENTO", b(v), d(dia)); }
    static Lanc per(String v, String dia) { return new Lanc("PERDAO", b(v), d(dia)); }
    static final LocalDate HOJE = LocalDate.parse("2026-10-15");

    public static void main(String[] a) {
        var r = SaldoCaderno.calcular(List.of(), HOJE);
        ck("vazio: saldo 0", r.saldo().signum() == 0); ck("vazio: sem desde", r.desde() == null); ck("vazio: 0 dias", r.diasAtraso() == 0);

        r = SaldoCaderno.calcular(List.of(div("50.00", "2026-10-01")), HOJE);
        ck("uma dívida: saldo 50", r.saldo().compareTo(b("50")) == 0); ck("desde 01/10", r.desde().equals(d("2026-10-01"))); ck("14 dias", r.diasAtraso() == 14);

        // FIFO: pagou 30 de duas dívidas (20 em 01/09 e 40 em 01/10) → a primeira quita, a segunda fica com 30 aberta; "desde" passa a ser 01/10
        r = SaldoCaderno.calcular(List.of(div("40", "2026-10-01"), div("20", "2026-09-01"), pag("30", "2026-10-05")), HOJE);
        ck("fifo: saldo 30", r.saldo().compareTo(b("30")) == 0); ck("fifo: desde 01/10", r.desde().equals(d("2026-10-01"))); ck("fifo: 14 dias", r.diasAtraso() == 14);
        ck("fifo: fiado 60 e pago 30", r.fiado().compareTo(b("60")) == 0 && r.pago().compareTo(b("30")) == 0);

        // pagamento parcial da dívida mais velha mantém a data dela
        r = SaldoCaderno.calcular(List.of(div("20", "2026-09-01"), div("40", "2026-10-01"), pag("10", "2026-10-05")), HOJE);
        ck("parcial: desde 01/09", r.desde().equals(d("2026-09-01"))); ck("parcial: 44 dias", r.diasAtraso() == 44); ck("parcial: saldo 50", r.saldo().compareTo(b("50")) == 0);

        // quitou tudo: sem saldo e sem atraso; perdão também abate
        r = SaldoCaderno.calcular(List.of(div("20", "2026-09-01"), pag("15", "2026-09-10"), per("5", "2026-09-20")), HOJE);
        ck("quitado: saldo 0", r.saldo().signum() == 0); ck("quitado: sem desde", r.desde() == null); ck("quitado: 0 dias", r.diasAtraso() == 0);

        // nunca fica negativo (crédito sobrando não vira saldo)
        r = SaldoCaderno.calcular(List.of(div("10", "2026-10-01"), pag("25", "2026-10-02")), HOJE);
        ck("sem saldo negativo", r.saldo().signum() == 0);

        // dívida de hoje: 0 dias; centavos arredondam
        r = SaldoCaderno.calcular(List.of(div("10.005", "2026-10-15")), HOJE);
        ck("hoje: 0 dias", r.diasAtraso() == 0); ck("arredonda centavos", r.saldo().compareTo(b("10.01")) == 0);

        // dado inválido é ignorado
        r = SaldoCaderno.calcular(List.of(new Lanc(null, b("5"), d("2026-10-01")), new Lanc("DIVIDA", null, d("2026-10-01")), new Lanc("OUTRO", b("9"), d("2026-10-01")), div("7", "2026-10-10")), HOJE);
        ck("ignora inválidos", r.saldo().compareTo(b("7")) == 0);

        System.out.println(falhas == 0 ? "✔ " + ok + " verificações ok" : "✘ " + falhas + " falha(s), " + ok + " ok");
        if (falhas > 0) System.exit(1);
    }
}
