package br.com.fruteira.retaguarda;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

public class Entities {
    @Entity public static class Fornecedor extends PanacheEntity {
        public String nome; public String documento; public String tipo; public String telefone; // CEASA, PRODUTOR_RURAL, EMPRESA
    }
    @Entity public static class Cliente extends PanacheEntity {
        public String nome; @Column(unique = true) public String cpf; public String telefone;
        public BigDecimal cashback = BigDecimal.ZERO;
    }
    @Entity public static class Cotacao extends PanacheEntity {
        public String produtoNome; public String fornecedor; public BigDecimal preco; public LocalDate data = LocalDate.now();
    }
    @Entity public static class Conta extends PanacheEntity {
        public String tipo; public String descricao; public String categoria; // tipo: PAGAR | RECEBER
        public BigDecimal valor; public LocalDate vencimento; public LocalDate dataPagamento; public boolean pago;
    }
    @Entity public static class Nota extends PanacheEntity {
        public Long vendaId; public Integer numero; public String chave; public String protocolo;
        public String status; public String cpf; public BigDecimal valor; public LocalDateTime data = LocalDateTime.now();
    }
    @Entity public static class Usuario extends PanacheEntity {
        public String nome; @Column(unique = true) public String login; public String perfil; public boolean ativo = true;
        @JsonIgnore public String pinHash;
        @Transient @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public String pin;
    }
}
