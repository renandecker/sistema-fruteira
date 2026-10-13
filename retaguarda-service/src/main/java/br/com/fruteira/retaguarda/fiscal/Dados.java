package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.util.List;

/** Dados de uma venda para montar a NFC-e. */
public record Dados(int numero, String cpf, String nome, List<Item> itens, List<Pag> pagamentos) {
    /** valor = subtotal cobrado (já com promoção); valorUnit = preço unitário cobrado. */
    public record Item(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor) {}
    /** tPag: 01 dinheiro · 03 crédito · 04 débito · 10 vale-alimentação · 11 vale-refeição · 17 Pix. Campos do cartão só para 03/04. */
    public record Pag(String tPag, BigDecimal valor, String cnpjCredenciadora, String tBand, String cAut) {}
}
