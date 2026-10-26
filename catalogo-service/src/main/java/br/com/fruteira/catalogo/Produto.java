package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
    // Classificação fiscal própria (opcional: vazio = vale o padrão da loja no .env). Usada na NFC-e: CFOP, CST/CSOSN, origem, PIS/COFINS e IBS/CBS.
    @Column(length = 4) public String cfop;               // 5102, 5405... (venda a consumidor final na UF)
    @Column(length = 3) public String cstIcms;            // CSOSN (Simples: 102, 103, 300, 400, 500) ou CST (regime normal: 00, 40, 41, 50, 60)
    @Column(length = 1) public String origemMercadoria;   // 0 nacional ... 8
    @Column(length = 2) public String cstPis, cstCofins;  // 01/02 tributado · 04 a 09 não tributado · 49/99 outras
    public BigDecimal aliqIcms, aliqPis, aliqCofins;      // % (só quando o CST tributa)
    @Column(length = 3) public String cstIbsCbs;          // CST do IBS/CBS (tabela oficial, 3 dígitos)
    @Column(length = 6) public String cClassTrib;         // classificação tributária do IBS/CBS (tabela oficial, 6 dígitos)
    public BigDecimal pRedIbsCbs;                          // redução de alíquota do IBS/CBS (%) quando a classificação prevê
    // Verificação do NCM (BrasilAPI / tabela oficial do Siscomex) — a NFC-e exige NCM de 8 dígitos válido e vigente em cada item
    @Column(length = 20) public String ncmStatus;      // VALIDO | INVALIDO | VENCIDO | AUSENTE | NAO_VERIFICADO
    @Column(length = 500) public String ncmDescricao;
    @Column(length = 20) public String ncmFonte;       // brasilapi | siscomex
    public LocalDate ncmFim;                           // fim da vigência
    public LocalDateTime ncmVerificadoEm;
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
