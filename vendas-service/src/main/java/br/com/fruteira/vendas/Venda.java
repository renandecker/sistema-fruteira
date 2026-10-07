package br.com.fruteira.vendas;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Entity
public class Venda extends PanacheEntity {
    public LocalDateTime data = LocalDateTime.now();
    public String cpfCliente;                 // fidelidade / cashback
    public BigDecimal total = BigDecimal.ZERO;
    public BigDecimal desconto = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) public Status status = Status.ABERTA;
    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL) public List<Item> itens = new ArrayList<>();
    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL) public List<Pagamento> pagamentos = new ArrayList<>();
    public enum Status { ABERTA, PAGA, CANCELADA }

    @Entity(name = "ItemVenda")
    public static class Item extends PanacheEntity {
        @ManyToOne @JsonIgnore public Venda venda;
        public Long produtoId; public String nome;
        public BigDecimal quantidade, precoUnit, subtotal, custoUnit;
        public BigDecimal precoOriginal, descontoTotal;   // desconto de promoção desta linha
        public String promocao;
    }
    @Entity(name = "PagamentoVenda")
    public static class Pagamento extends PanacheEntity {
        @ManyToOne @JsonIgnore public Venda venda;
        public String meio; // DINHEIRO, PIX, CREDITO, DEBITO, VALE_ALIMENTACAO, VALE_REFEICAO
        public BigDecimal valor;
        // cartão via TEF (necessários à NFC-e)
        public Long tefId; public String nsu, autorizacao, bandeira, adquirente, cnpjCredenciadora;
        // Pix recebido (txid e endToEndId — a NFC-e usa tPag 17)
        public String pixTxid, pixE2e;
    }
}
