package br.com.fruteira.vendas;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Turno (gaveta) de um operador: abertura com fundo de troco, sangrias/suprimentos e fechamento cego. */
@Entity
public class TurnoCaixa extends PanacheEntity {
    @Column(nullable = false) public String operador;
    public LocalDateTime abertura = LocalDateTime.now();
    public BigDecimal fundoTroco = BigDecimal.ZERO;
    public LocalDateTime fechamento;
    public BigDecimal contadoFechamento, esperadoFechamento, diferenca;   // preenchidos ao fechar (só o gerente vê)
    @Column(length = 10) public String status = "ABERTO";                 // ABERTO | FECHADO
}
