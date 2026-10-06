package br.com.fruteira.tef;

import java.math.BigDecimal;

/** Mensagens trocadas entre o front, o agente e o provedor TEF. */
public final class TefModelo {
    private TefModelo() {}

    /** tipo: CREDITO_A_VISTA | CREDITO_PARCELADO | DEBITO */
    public record Pedido(String requisicao, BigDecimal valor, String tipo, Integer parcelas, String operador) {}
    /** estado: AGUARDANDO_CARTAO | AGUARDANDO_SENHA | PROCESSANDO (mensagens ao operador, em tempo real) */
    public record Progresso(String estado, String mensagem) {}
    /** status: APROVADA | NEGADA | CANCELADA | ERRO */
    public record Resultado(String status, String mensagem, String nsu, String nsuHost, String autorizacao, String bandeira,
                            String adquirente, String cnpjCredenciadora, String cartao, String comprovanteCliente, String comprovanteLoja) {}
    public record Estorno(String requisicao, String nsu, BigDecimal valor, String data) {}
}
