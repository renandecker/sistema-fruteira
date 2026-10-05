package br.com.fruteira.estoque;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Estoque {
    public enum Tipo { ENTRADA, VENDA, PERDA, AVARIA, PRODUCAO_SAIDA, PRODUCAO_ENTRADA, AJUSTE }

    @Entity(name = "Saldo")
    public static class Saldo extends PanacheEntity {
        @Column(unique = true) public Long produtoId;
        public BigDecimal quantidade = BigDecimal.ZERO; // sempre na UNIDADE DE VENDA (kg, un)
        public BigDecimal custoMedio = BigDecimal.ZERO;
    }
    @Entity(name = "Movimento")
    public static class Movimento extends PanacheEntity {
        public Long produtoId;
        @Enumerated(EnumType.STRING) public Tipo tipo;
        public BigDecimal quantidade;
        public BigDecimal custoUnitario;
        public String motivo;
        public Long fornecedorId; public String fornecedor;   // entradas: de quem comprou
        public LocalDateTime data = LocalDateTime.now();
    }
}
