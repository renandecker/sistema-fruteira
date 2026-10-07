package br.com.fruteira.vendas;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cobrança Pix dinâmica. Ciclo: ATIVA → CONCLUIDA (pago, valor exato) | DIVERGENTE (valor diferente) | EXPIRADA | CANCELADA;
 * CONCLUIDA/DIVERGENTE → DEVOLVIDA. A venda é vinculada (vendaId) quando o Pix recebido paga uma venda.
 */
@Entity
public class PixCobranca extends PanacheEntity {
    @Column(unique = true) public String txid;           // 26 a 35 caracteres [a-zA-Z0-9] (padrão Bacen)
    public BigDecimal valor;
    public String descricao;
    public String status = "ATIVA";
    public String provedor;
    @Column(length = 1000) public String pixCopiaECola;
    public String location;
    public LocalDateTime criadaEm = LocalDateTime.now();
    public LocalDateTime expiraEm;
    public LocalDateTime ultimaConsulta;                  // última conferência ativa no PSP (rede de segurança se o webhook falhar)
    @Column(unique = true) public String endToEndId;      // identificador do pagamento; garante a idempotência do webhook
    public BigDecimal valorPago;
    public LocalDateTime pagoEm;
    public Long vendaId;
    public String operador, terminal;
    public String idDevolucao;
    public LocalDateTime devolvidaEm;
}
