package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Promoção de UM produto, com prazo (início e fim inclusivos). */
@Entity
public class Promocao extends PanacheEntity {
    public Long produtoId;
    public String tipo;            // PERCENTUAL (valor = % de desconto) | PRECO_FIXO (valor = preço promocional por unidade/kg)
    public BigDecimal valor;
    public LocalDate inicio;
    public LocalDate fim;
    @Column(columnDefinition = "boolean default true") public Boolean ativo = true;

    public BigDecimal precoFinal(BigDecimal base) {
        if ("PERCENTUAL".equals(tipo)) return base.multiply(BigDecimal.ONE.subtract(valor.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
    public String situacao(LocalDate hoje) {
        if (Boolean.FALSE.equals(ativo)) return "DESATIVADA";
        if (hoje.isBefore(inicio)) return "AGENDADA";
        if (hoje.isAfter(fim)) return "ENCERRADA";
        return "EM_VIGOR";
    }
}
