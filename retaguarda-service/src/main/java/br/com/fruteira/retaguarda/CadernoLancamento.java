package br.com.fruteira.retaguarda;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Uma linha do caderno (fiado) de um cliente. tipo: DIVIDA (fiou) | PAGAMENTO (pagou) | PERDAO (baixa sem receber, só gerente). */
@Entity(name = "CadernoLancamento")
public class CadernoLancamento extends PanacheEntity {
    public String cpf;
    public String tipo;
    public BigDecimal valor;
    /** Venda que originou a dívida (só em DIVIDA); garante que a mesma venda não é lançada duas vezes. */
    public Long vendaId;
    public LocalDateTime data = LocalDateTime.now();
    public String usuario;
    public String obs;
    @Column(columnDefinition = "boolean default false") public Boolean cancelado = false;
}
