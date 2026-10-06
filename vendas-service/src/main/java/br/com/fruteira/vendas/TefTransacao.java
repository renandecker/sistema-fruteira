package br.com.fruteira.vendas;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transação de cartão (TEF). Ciclo: PENDENTE → APROVADA | NEGADA | CANCELADA | ERRO;
 * APROVADA → CONFIRMADA (venda gravada e comprovante impresso) | DESFEITA (venda não concluída); CONFIRMADA → ESTORNADA.
 */
@Entity
public class TefTransacao extends PanacheEntity {
    @Column(unique = true) public String requisicao;       // nº único enviado ao agente/TEF (ex.: TEF20261005000012)
    public BigDecimal valor;
    public String tipo;                                    // CREDITO_A_VISTA | CREDITO_PARCELADO | DEBITO
    public Integer parcelas = 1;
    public String status = "PENDENTE";
    public Long vendaId;                                   // preenchido quando a venda é gravada com este pagamento
    public String nsu, nsuHost, autorizacao, bandeira, adquirente, cnpjCredenciadora, cartao, terminal, operador;
    @Column(length = 4000) public String comprovanteCliente;
    @Column(length = 4000) public String comprovanteLoja;
    @Column(length = 500) public String mensagem;
    public LocalDateTime criadoEm = LocalDateTime.now();
    public LocalDateTime atualizadoEm = LocalDateTime.now();
    public LocalDateTime confirmadoEm;
}
