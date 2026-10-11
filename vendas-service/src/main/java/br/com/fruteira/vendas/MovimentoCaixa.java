package br.com.fruteira.vendas;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Retirada (SANGRIA, vai para o cofre) ou entrada (SUPRIMENTO, reforço de troco) de dinheiro da gaveta. */
@Entity
public class MovimentoCaixa extends PanacheEntity {
    public Long turnoId;
    @Column(length = 12) public String tipo;           // SANGRIA | SUPRIMENTO
    public BigDecimal valor;                           // valor CONTADO pelo operador
    @Column(length = 200) public String motivo;
    public String operador;
    public LocalDateTime data = LocalDateTime.now();
    public boolean acimaDoSaldo;                       // sangria maior que o saldo do sistema (só o gerente vê)
}
