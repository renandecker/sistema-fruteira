package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Produto extends PanacheEntity {
    public String nome;
    @Enumerated(EnumType.STRING) public Unidade unidade = Unidade.KG; // KG, UN, MACO, CX
    public BigDecimal precoVarejo;
    public BigDecimal precoAtacado;
    public BigDecimal precoPromocional;      // "sacolão do dia" / desconto por validade
    @Column(unique = true) public Integer plu;   // atalho numérico rápido (1 = Banana Prata)
    public String codigoBarras;
    public String categoria;                 // folhosas, frutas de época...
    public String fotoUrl;                   // botão touch com foto
    public String ncm;
    public BigDecimal taxaPerdaPct = BigDecimal.ZERO; // perda esperada p/ markup
    public BigDecimal custoMedio = BigDecimal.ZERO;
    public boolean ativo = true;

    public enum Unidade { KG, UN, MACO, CX }

    /** Preço efetivo: promoção > atacado (se solicitado) > varejo */
    public BigDecimal precoEfetivo(boolean atacado) {
        if (precoPromocional != null) return precoPromocional;
        return (atacado && precoAtacado != null) ? precoAtacado : precoVarejo;
    }
    /** Preço sugerido = custo / (1 - perda) * (1 + margem) */
    public BigDecimal precoSugerido(BigDecimal margemPct) {
        BigDecimal fator = BigDecimal.ONE.subtract(taxaPerdaPct.movePointLeft(2));
        return custoMedio.divide(fator, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.ONE.add(margemPct.movePointLeft(2)))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
