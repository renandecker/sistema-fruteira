package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dados de uma NF-e (modelo 55) de venda a empresa: destinatário completo, itens, frete, desconto, duplicatas (a prazo) e pagamento.
 * {@code consumidorFinal}: true = a empresa usa a mercadoria (indFinal 1); false = revenda/insumo (padrão de venda a mercado, restaurante etc.).
 */
public record DadosNfe(int numero, Dest dest, List<Dados.Item> itens, List<Dados.Pag> pagamentos, List<Parcela> duplicatas, boolean consumidorFinal,
                       int modFrete, BigDecimal vFrete, BigDecimal vDesc, String natOp, String infCpl, int indPres) {
    public record Dest(String doc, String nome, String ie, String email, String fone, String logradouro, String numero, String complemento,
                       String bairro, String cMun, String municipio, String uf, String cep) {}
    public record Parcela(LocalDate venc, BigDecimal valor) {}

    /** 1 = operação interna (mesma UF do emitente) · 2 = interestadual. */
    public int idDest(String ufEmitente) { return dest != null && dest.uf() != null && !dest.uf().isBlank() && !dest.uf().trim().equalsIgnoreCase(ufEmitente) ? 2 : 1; }

    /** 1 = contribuinte do ICMS (tem IE) · 2 = contribuinte isento · 9 = não contribuinte. */
    public int indIEDest() {
        String ie = dest == null || dest.ie() == null ? "" : dest.ie().trim();
        if (ie.isEmpty()) return 9;
        return ie.equalsIgnoreCase("ISENTO") ? 2 : 1;
    }
    public BigDecimal frete() { return vFrete == null ? BigDecimal.ZERO : vFrete; }
    public BigDecimal desconto() { return vDesc == null ? BigDecimal.ZERO : vDesc; }
    public BigDecimal totalProdutos() { return itens.stream().map(Dados.Item::valor).reduce(BigDecimal.ZERO, BigDecimal::add); }
    public BigDecimal totalNota() { return totalProdutos().add(frete()).subtract(desconto()); }
}
