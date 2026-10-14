package br.com.fruteira.vendas;

/**
 * Situação de uma cobrança Pix NO PROVEDOR (é o que {@link PixProviderService#consultar} devolve). Os nomes seguem o padrão Bacen.
 * A cobrança guardada no sistema ({@link PixCobranca#status}) usa também estados próprios (DIVERGENTE, DEVOLVIDA, CANCELADA);
 * REMOVIDA_PELO_USUARIO_RECEBEDOR vira CANCELADA.
 */
public enum PixStatus {
    ATIVA,                                  // gerada e aguardando o pagamento
    CONCLUIDA,                              // paga
    EXPIRADA,                               // venceu sem pagamento
    REMOVIDA_PELO_USUARIO_RECEBEDOR;        // cancelada pela loja (também cobre REMOVIDA_PELO_PSP)

    /** Cobrança que não muda mais no provedor. */
    public boolean encerrada() { return this != ATIVA; }

    /** Converte o texto do provedor. Desconhecido ou vazio = ATIVA (continua consultando; o vencimento local expira). */
    public static PixStatus deTexto(String texto) {
        if (texto == null) return ATIVA;
        String t = texto.trim().toUpperCase();
        if (t.startsWith("REMOVIDA")) return REMOVIDA_PELO_USUARIO_RECEBEDOR;
        for (PixStatus s : values()) if (s.name().equals(t)) return s;
        return ATIVA;
    }
}
