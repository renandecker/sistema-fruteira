package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.util.List;

/** Dados de uma venda para montar a NFC-e. {@code cpf} guarda o documento do consumidor: CPF (11 dígitos) ou CNPJ (14). */
public record Dados(int numero, String cpf, String nome, List<Item> itens, List<Pag> pagamentos) {
    /** valor = subtotal cobrado (já com promoção); valorUnit = preço unitário cobrado. */
    public record Item(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor,
                       String cfop, String cstIcms, String cstPis, String cstCofins, String origem,
                       BigDecimal aliqIcms, BigDecimal aliqPis, BigDecimal aliqCofins,
                       String cstIbsCbs, String cClassTrib, BigDecimal pRedIbsCbs) {
        /** Classificação própria sem alíquotas nem IBS/CBS (testes e produtos isentos/alíquota zero). */
        public Item(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor,
                    String cfop, String cstIcms, String cstPis, String cstCofins, String origem) {
            this(codigo, nome, ncm, unidade, qtd, valorUnit, valor, cfop, cstIcms, cstPis, cstCofins, origem, null, null, null, null, null, null);
        }
        /** Sem classificação própria: vale o padrão da loja (FISCAL_CFOP / CSOSN-CST / PIS / COFINS). */
        public Item(String codigo, String nome, String ncm, String unidade, BigDecimal qtd, BigDecimal valorUnit, BigDecimal valor) {
            this(codigo, nome, ncm, unidade, qtd, valorUnit, valor, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
    /** tPag: 01 dinheiro · 03 crédito · 04 débito · 10 vale-alimentação · 11 vale-refeição · 17 Pix · 05 crédito loja (caderno/fiado). Campos do cartão só para 03/04. */
    public record Pag(String tPag, BigDecimal valor, String cnpjCredenciadora, String tBand, String cAut, Integer parcelas) {
        public Pag(String tPag, BigDecimal valor, String cnpjCredenciadora, String tBand, String cAut) { this(tPag, valor, cnpjCredenciadora, tBand, cAut, null); }
        /** indPag: 0 = à vista · 1 = a prazo (crédito parcelado, crédito da loja/caderno). */
        public int indPag() { return "05".equals(tPag) || (parcelas != null && parcelas > 1 && "03".equals(tPag)) ? 1 : 0; }
    }
}
