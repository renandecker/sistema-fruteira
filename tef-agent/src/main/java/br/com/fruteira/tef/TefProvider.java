package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Ponte com o TEF de verdade. Para usar um provedor (SiTef/Software Express, Cappta, PayGo, Linx...) crie uma classe
 * @ApplicationScoped que implemente esta interface, devolva o nome em nome() e selecione com TEF_PROVIDER no .env.
 */
public interface TefProvider {
    String nome();
    /** false = o provedor está só reservado (seleção e variáveis do .env já existem), mas ainda não fala com a maquininha. */
    default boolean implementado() { return true; }
    /** Variáveis do .env que faltam ou estão inválidas para este provedor (vazio = configuração completa). */
    default List<String> problemas() { return List.of(); }
    /** Frase para mostrar ao gerente no teste de produção. */
    default String descricao() { return nome(); }
    boolean pinpadConectado();
    /** Executa a transação (bloqueante). Chame progresso.accept(...) para mostrar mensagens ao operador; consulte cancelado.getAsBoolean(). */
    Resultado executar(Pedido pedido, Consumer<Progresso> progresso, BooleanSupplier cancelado) throws Exception;
    /** Confirma a transação aprovada (após a venda gravada e o comprovante impresso). */
    void confirmar(String requisicao) throws Exception;
    /** Desfaz a transação aprovada (venda não concluída / queda). */
    void desfazer(String requisicao) throws Exception;
    /** Cancelamento administrativo (estorno) de transação já confirmada. */
    Resultado estornar(Estorno estorno) throws Exception;
}
