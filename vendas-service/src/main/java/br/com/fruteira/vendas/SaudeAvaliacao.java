package br.com.fruteira.vendas;

import java.util.List;

/**
 * Regras (puras, sem Quarkus: testadas só com JDK) que transformam o que a rotina de saúde observou em um resultado por meio de pagamento.
 * Só entra na conta o que está CONFIGURADO e DEFINIDO: PIX_PROVIDER escolhe o provedor Pix; TEF_PROVIDER escolhe o TEF (vazio ou "nenhum" = não confere).
 */
public final class SaudeAvaliacao {
    private SaudeAvaliacao() {}

    /** Do melhor para o pior; NAO_CONFIGURADO não pesa no resultado geral. */
    public enum Status { OK, ALERTA, FALHA, NAO_CONFIGURADO }
    public record Item(String nome, String provedor, Status status, String detalhe, long ms) {}

    /** Pior status entre os itens configurados (tudo não configurado = OK: não há o que falhar). */
    public static Status geral(List<Item> itens) {
        Status pior = Status.OK;
        for (Item i : itens) if (i.status() != Status.NAO_CONFIGURADO && i.status().ordinal() > pior.ordinal()) pior = i.status();
        return pior;
    }

    public static boolean tefDefinido(String provedor) { return provedor != null && !provedor.isBlank() && !"nenhum".equalsIgnoreCase(provedor.trim()); }

    /**
     * @param definido     TEF_PROVIDER do .env (o TEF que o sistema deve estar usando)
     * @param noAgente     provedor que o agente respondeu em /tef/status (null = não respondeu)
     * @param pinpad       pinpad conectado segundo o agente (null = desconhecido)
     * @param pendencias   transações pendentes de conciliação no agente
     * @param semVenda     cartões aprovados no servidor há mais de 30 min sem venda vinculada
     * @param erro         motivo de o agente não ter respondido
     */
    public static Item tef(String definido, String noAgente, Boolean pinpad, int pendencias, long semVenda, String erro, long ms) {
        if (!tefDefinido(definido)) return new Item("TEF", null, Status.NAO_CONFIGURADO, "TEF não definido (TEF_PROVIDER vazio ou \"nenhum\"): não conferido", ms);
        String nome = definido.trim().toLowerCase();
        if (noAgente == null) return new Item("TEF", nome, Status.FALHA, "O agente TEF não respondeu" + (erro == null || erro.isBlank() ? "" : ": " + erro), ms);
        if (!noAgente.equalsIgnoreCase(nome)) return new Item("TEF", nome, Status.FALHA, "O .env define o TEF \"" + nome + "\", mas o agente em execução usa \"" + noAgente + "\" (reinicie o agente depois de mudar TEF_PROVIDER)", ms);
        if (Boolean.FALSE.equals(pinpad)) return new Item("TEF", nome, Status.FALHA, "Agente no ar, mas o pinpad está desconectado", ms);
        List<String> avisos = new java.util.ArrayList<>();
        if (pendencias > 0) avisos.add(pendencias + " transação(ões) pendente(s) de conciliação no agente (Transações TEF → Resolver)");
        if (semVenda > 0) avisos.add(semVenda + " cartão(ões) aprovado(s) há mais de 30 min sem venda vinculada (conferir/estornar em Transações TEF)");
        if (!avisos.isEmpty()) return new Item("TEF", nome, Status.ALERTA, String.join("; ", avisos), ms);
        return new Item("TEF", nome, Status.OK, "agente e pinpad ok" + ("simulador".equals(nome) ? " (simulador: não cobra de verdade)" : ""), ms);
    }

    /**
     * @param provedor      PIX_PROVIDER (nome do enum)
     * @param simulador     true se for o simulador
     * @param problemas     o que falta configurar neste provedor
     * @param erroConexao   falha ao autenticar/consultar o provedor (null = ok)
     * @param semWebhook    provedor real sem URL pública de webhook (só consulta periódica)
     * @param semVenda      Pix recebido há mais de 30 min sem venda vinculada
     */
    public static Item pix(String provedor, boolean simulador, List<String> problemas, String erroConexao, boolean semWebhook, long semVenda, long ms) {
        if (problemas != null && !problemas.isEmpty()) return new Item("PIX", provedor, Status.FALHA, "Configuração incompleta: " + String.join("; ", problemas), ms);
        if (erroConexao != null) return new Item("PIX", provedor, Status.FALHA, "Não consegui falar com o provedor: " + erroConexao, ms);
        List<String> avisos = new java.util.ArrayList<>();
        if (semWebhook) avisos.add("sem URL pública de webhook (o pagamento só é percebido pela consulta a cada 10 s)");
        if (semVenda > 0) avisos.add(semVenda + " Pix recebido(s) há mais de 30 min sem venda vinculada (ver Transações PIX)");
        if (!avisos.isEmpty()) return new Item("PIX", provedor, Status.ALERTA, String.join("; ", avisos), ms);
        return new Item("PIX", provedor, Status.OK, simulador ? "simulador (não cobra de verdade)" : "provedor respondeu e a configuração está completa", ms);
    }

    /** Mensagem de erro de rede/provedor para tela e log: sem quebra de linha, sem credenciais evidentes, no máximo 300 caracteres. */
    public static String limpar(String msg) {
        if (msg == null) return "erro desconhecido";
        String s = msg.replaceAll("[\\r\\n]+", " ").replaceAll("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+", "$1 ***")
            .replaceAll("(?i)(access_token|client_secret|token)([\"'=: ]+)[A-Za-z0-9._~+/=-]{6,}", "$1$2***").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
