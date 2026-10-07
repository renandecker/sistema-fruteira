package br.com.fruteira.vendas;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ponte com o PSP / banco. Implementações: "simulador" (testes) e "psp" (API Pix padrão do Banco Central: /v2/cob, mTLS/OAuth2).
 * Para outro PSP, crie uma classe @ApplicationScoped que implemente esta interface e selecione com PIX_PROVIDER.
 */
public interface PixProvider {
    String nome();
    record Criada(String pixCopiaECola, String location) {}
    /** status: ATIVA | CONCLUIDA | REMOVIDA */
    record Situacao(String status, String endToEndId, BigDecimal valor, LocalDateTime horario) {}

    Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception;
    Situacao consultar(String txid) throws Exception;
    void cancelar(String txid) throws Exception;
    void devolver(String endToEndId, String idDevolucao, BigDecimal valor) throws Exception;
    /** Registra no PSP a URL que receberá as notificações de pagamento. */
    default void registrarWebhook(String url) throws Exception { throw new UnsupportedOperationException("Este provedor não registra webhook"); }
}
