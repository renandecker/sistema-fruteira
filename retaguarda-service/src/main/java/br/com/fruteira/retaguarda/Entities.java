package br.com.fruteira.retaguarda;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;

public class Entities {
    @Entity(name = "Fornecedor") public static class Fornecedor extends PanacheEntity {
        public String nome; public String documento; public String tipo; public String telefone; // CEASA, PRODUTOR_RURAL, EMPRESA
        @Column(columnDefinition = "boolean default true") public Boolean ativo = true;
    }
    @Entity(name = "Cliente") public static class Cliente extends PanacheEntity {
        public String nome; @Column(unique = true) public String cpf; public String telefone;
        public BigDecimal cashback = BigDecimal.ZERO;
        /** Teto de dívida no caderno (fiado); nulo = usa o limite padrão do sistema. */
        public BigDecimal limiteCaderno;
        @Column(columnDefinition = "boolean default true") public Boolean ativo = true;
    }
    @Entity(name = "Cotacao") public static class Cotacao extends PanacheEntity {
        public String produtoNome; public String fornecedor; public BigDecimal preco; public LocalDate data = LocalDate.now();
        @Column(columnDefinition = "boolean default true") public Boolean ativo = true;
    }
    @Entity(name = "Conta") public static class Conta extends PanacheEntity {
        public String tipo; public String descricao; public String categoria; // tipo: PAGAR | RECEBER
        public BigDecimal valor; public LocalDate vencimento; public LocalDate dataPagamento; public boolean pago;
        @Column(columnDefinition = "boolean default true") public Boolean ativo = true;
    }
    @Entity(name = "Nota") public static class Nota extends PanacheEntity {
        public Long vendaId; public Integer numero; public String chave; public String protocolo;
        public String status; public String cpf; public BigDecimal valor; public LocalDateTime data = LocalDateTime.now();
        @Column(length = 2000) public String cartoes;   // JSON: pagamentos em cartão (tPag, CNPJ credenciadora, bandeira, autorização, NSU)
        // --- emissão real (SEFAZ-RS) ---
        public String nome;                              // nome do consumidor (opcional; vai em <dest><xNome>)
        public Integer cStat, tpEmis; @Column(length = 500) public String xMotivo; public String cNF, dhAutorizacao;
        public String provider;                          // SIMULADOR | SEFAZ
        public Boolean erroCpf;                          // rejeição por CPF/destinatário: o caixa pode corrigir ou emitir sem CPF
        @Column(length = 600) public String qrCode; public String urlChave;
        @JsonIgnore @Column(length = 200000) public String xml;          // XML assinado (ou nfeProc, quando autorizada)
        @JsonIgnore @Column(length = 20000) public String payload;       // itens/pagamentos da venda (para reenviar após corrigir o CPF)
    }
    @Entity(name = "Auditoria") public static class Auditoria extends PanacheEntity {
        public LocalDateTime data = LocalDateTime.now();
        public String usuario; public String perfil; public String acao; public String entidade; public String entidadeId; public String origem;
        @Column(length = 1000) public String descricao;
        @Column(length = 4000) public String antes;    // JSON antes da alteração
        @Column(length = 4000) public String depois;   // JSON depois da alteração
    }
    @Entity(name = "Usuario") public static class Usuario extends PanacheEntity {
        public String nome; @Column(unique = true) public String login; public String perfil; public boolean ativo = true;
        @JsonIgnore public String pinHash;
        @Transient @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public String pin;
    }
}
