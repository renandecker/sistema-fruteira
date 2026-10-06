package br.com.fruteira.tef;

import br.com.fruteira.tef.TefModelo.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * PONTO DE EXTENSÃO para o TEF real (SiTef / CliSiTef). NÃO está implementado: exige a DLL CliSiTef32/64 licenciada pelo
 * seu provedor, o contrato e a homologação. Roteiro (JNA sobre a DLL):
 *   1) ConfiguraIntSiTefInterativo(ip do SiTef, código da loja, terminal, ...)           — uma vez, ao subir o agente
 *   2) IniciaFuncaoSiTefInterativo(modalidade, valor, nº do documento = requisicao, data, hora, operador)
 *   3) laço ContinuaFuncaoSiTefInterativo(...) — repassa as mensagens do pinpad ao operador (progresso.accept)
 *      e coleta o resultado (NSU, autorização, bandeira, comprovantes) para montar o TefModelo.Resultado
 *   4) FinalizaTransacaoSiTefInterativo(confirma=1 | desfaz=0, ...) em confirmar()/desfazer()
 * Selecione com TEF_PROVIDER=clisitef depois de implementar. Enquanto isso, o agente responde ERRO ao usá-lo.
 */
@ApplicationScoped
public class CliSiTefProvider implements TefProvider {
    private static final String MSG = "Provedor CliSiTef ainda não implementado neste agente (veja CliSiTefProvider.java). Use TEF_PROVIDER=simulador para testes.";
    @Override public String nome() { return "clisitef"; }
    @Override public boolean pinpadConectado() { return false; }
    @Override public Resultado executar(Pedido p, Consumer<Progresso> progresso, BooleanSupplier cancelado) { throw new UnsupportedOperationException(MSG); }
    @Override public void confirmar(String requisicao) { throw new UnsupportedOperationException(MSG); }
    @Override public void desfazer(String requisicao) { throw new UnsupportedOperationException(MSG); }
    @Override public Resultado estornar(Estorno e) { throw new UnsupportedOperationException(MSG); }
}
