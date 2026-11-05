package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Base dos provedores de adquirente (Stone, Cielo, Banco do Brasil) que ainda NÃO falam com a maquininha.
 * Falar de verdade exige contrato com a adquirente, o SDK/instalador dela (ou um TEF intermediário: SiTef, PayGo...) e homologação,
 * coisas que não existem neste repositório. O que já funciona: escolher o provedor por caixa (TEF_PROVIDER no .env DAQUELE computador),
 * validar as variáveis obrigatórias e mostrar o resultado no teste de produção. Ao tentar cobrar, o agente responde ERRO com o motivo
 * (a venda não é concluída no cartão e o caixa pode usar o modo manual).
 */
abstract class AdquirenteReservado implements TefProvider {
    private final String nome, adquirente;
    AdquirenteReservado(String nome, String adquirente) { this.nome = nome; this.adquirente = adquirente; }
    /** Pares {nome da variável no .env, valor atual} que são obrigatórios. */
    abstract List<String[]> obrigatorias();

    @Override public String nome() { return nome; }
    @Override public boolean implementado() { return false; }
    @Override public boolean pinpadConectado() { return false; }
    @Override public List<String> problemas() {
        return obrigatorias().stream().filter(v -> v[1] == null || v[1].isBlank()).map(v -> v[0] + " não definido no .env deste computador").toList();
    }
    @Override public String descricao() { return adquirente + ": seleção e variáveis prontas; integração com a maquininha ainda não implementada (exige contrato, SDK e homologação)"; }

    private UnsupportedOperationException nao() {
        return new UnsupportedOperationException("Integração com " + adquirente + " ainda não implementada neste agente. Use TEF_PROVIDER=simulador para testes ou deixe o caixa em modo manual (TEF/maquininha: Manual).");
    }
    @Override public Resultado executar(Pedido p, Consumer<Progresso> progresso, BooleanSupplier cancelado) { throw nao(); }
    @Override public void confirmar(String requisicao) { throw nao(); }
    @Override public void desfazer(String requisicao) { throw nao(); }
    @Override public Resultado estornar(Estorno e) { throw nao(); }
}
