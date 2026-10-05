package br.com.fruteira.catalogo;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Preenche, em cada produto, a promoção vigente HOJE (preço promocional, % de desconto e prazo). */
@ApplicationScoped
public class PromocaoService {
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");

    public void aplicar(Collection<Produto> produtos) {
        LocalDate hoje = LocalDate.now();
        Map<Long, Promocao> vigentes = new HashMap<>();
        for (Promocao pr : Promocao.<Promocao>list("ativo = true and inicio <= ?1 and fim >= ?1", hoje)) vigentes.putIfAbsent(pr.produtoId, pr);
        for (Produto p : produtos) {
            p.precoPromocional = null; p.promocaoId = null; p.promocaoFim = null; p.promocaoDescricao = null; p.descontoPct = null;
            Promocao pr = vigentes.get(p.id);
            if (pr == null || p.precoVarejo == null || p.precoVarejo.signum() <= 0) continue;
            BigDecimal promo = pr.precoFinal(p.precoVarejo);
            if (promo.compareTo(p.precoVarejo) >= 0) continue;           // promoção que não baixa o preço é ignorada
            BigDecimal pct = p.precoVarejo.subtract(promo).multiply(BigDecimal.valueOf(100)).divide(p.precoVarejo, 1, RoundingMode.HALF_UP);
            p.precoPromocional = promo; p.promocaoId = pr.id; p.promocaoFim = pr.fim; p.descontoPct = pct;
            p.promocaoDescricao = pct.stripTrailingZeros().toPlainString() + "% OFF até " + DM.format(pr.fim);
        }
    }
}
