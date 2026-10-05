package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Produto extends PanacheEntity {
    public String nome;
    @Enumerated(EnumType.STRING) public Unidade unidade = Unidade.KG; // KG, UN, MACO, CX
    public BigDecimal precoVarejo;
    public BigDecimal precoAtacado;
    // Promoção vigente (calculada a partir da tela Promoções; NÃO é gravada no produto)
    @Transient public BigDecimal precoPromocional;
    @Transient public Long promocaoId;
    @Transient public LocalDate promocaoFim;
    @Transient public String promocaoDescricao;
    @Transient public BigDecimal descontoPct;
    @Column(unique = true) public Integer plu;   // atalho numérico rápido (1 = Banana Prata)
    public String codigoBarras;
    @Column(length = 60) public String imagem;     // chave do catálogo de imagens (ex.: "banana")
    @Column(length = 1) public String atalho;   // opcional: 1 número (0-9) ou letra (A-Z) usado com Ctrl no PDV
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
