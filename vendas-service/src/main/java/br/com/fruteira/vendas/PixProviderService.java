package br.com.fruteira.vendas;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrato comum de todos os provedores de Pix. Cada implementação é um bean @ApplicationScoped que informa seu
 * {@link PixProvider}; o {@link PixService} escolhe o que casa com PIX_PROVIDER. Configuração: {@link PixConfig}.
 * Para um provedor novo: acrescente o valor em {@link PixProvider}, as configurações em {@link PixConfig} e implemente esta interface.
 */
public interface PixProviderService {
    PixProvider provider();

    record Criada(String pixCopiaECola, String location) {}
    record Situacao(PixStatus status, String endToEndId, BigDecimal valor, LocalDateTime horario) {}
    /** Um pagamento avisado pelo provedor (webhook). */
    record Notificacao(String txid, String endToEndId, BigDecimal valor, LocalDateTime horario) {}

    Criada criar(String txid, BigDecimal valor, int expiracaoSeg, String descricao) throws Exception;
    Situacao consultar(String txid) throws Exception;
    void cancelar(String txid) throws Exception;
    void devolver(String endToEndId, String idDevolucao, BigDecimal valor) throws Exception;
    /** Registra no provedor a URL que receberá as notificações de pagamento. */
    default void registrarWebhook(String url) throws Exception { throw new UnsupportedOperationException("Este provedor não registra webhook"); }

    /** Extrai os pagamentos do corpo do webhook. Padrão: formato do Bacen {"pix":[{endToEndId, txid, valor, horario}]}; provedores com payload próprio sobrescrevem. */
    default List<Notificacao> interpretarWebhook(JsonNode corpo) {
        List<Notificacao> l = new ArrayList<>();
        if (corpo == null) return l;
        for (JsonNode p : corpo.path("pix")) {
            String txid = p.path("txid").asText(null), e2e = p.path("endToEndId").asText(null);
            if (txid == null || e2e == null) continue;
            LocalDateTime quando = LocalDateTime.now();
            try { quando = OffsetDateTime.parse(p.path("horario").asText()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignorada) { /* usa agora */ }
            l.add(new Notificacao(txid, e2e, new BigDecimal(p.path("valor").asText("0")), quando));
        }
        return l;
    }

    /** Confere, sem criar cobrança, que dá para falar com o provedor (autenticação/consulta simples). Lança exceção se falhar. Usado pela rotina de saúde (SAUDE_INTERVALO). */
    default void testarConexao() throws Exception { }

    /** O que falta configurar para este provedor funcionar (vazio = pronto). Aparece em /pix/status e no log de partida. */
    default List<String> problemasDeConfiguracao() { return List.of(); }
}
