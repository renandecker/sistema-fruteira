package br.com.fruteira.vendas;

import br.com.fruteira.vendas.SaudeAvaliacao.Item;
import br.com.fruteira.vendas.SaudeAvaliacao.Status;
import java.util.List;

public class SaudeTeste {
    static int ok = 0, falhas = 0;
    static void check(String nome, boolean cond) { if (cond) ok++; else { falhas++; System.out.println("FALHOU: " + nome); } }

    public static void main(String[] a) {
        // TEF: só o definido é conferido
        check("tef vazio não conferido", SaudeAvaliacao.tef("", null, null, 0, 0, null, 0).status() == Status.NAO_CONFIGURADO);
        check("tef null não conferido", SaudeAvaliacao.tef(null, null, null, 0, 0, null, 0).status() == Status.NAO_CONFIGURADO);
        check("tef nenhum não conferido", SaudeAvaliacao.tef(" Nenhum ", "clisitef", false, 3, 3, null, 0).status() == Status.NAO_CONFIGURADO);
        check("tef definido sem resposta = falha", SaudeAvaliacao.tef("clisitef", null, null, 0, 0, "Connection refused", 5).status() == Status.FALHA);
        check("falha cita o erro", SaudeAvaliacao.tef("clisitef", null, null, 0, 0, "Connection refused", 5).detalhe().contains("Connection refused"));
        check("agente com outro provedor = falha", SaudeAvaliacao.tef("clisitef", "simulador", true, 0, 0, null, 0).status() == Status.FALHA);
        check("provedor compara sem caixa", SaudeAvaliacao.tef("CliSiTef", "clisitef", true, 0, 0, null, 0).status() == Status.OK);
        check("pinpad off = falha", SaudeAvaliacao.tef("clisitef", "clisitef", false, 0, 0, null, 0).status() == Status.FALHA);
        check("pinpad desconhecido não falha", SaudeAvaliacao.tef("clisitef", "clisitef", null, 0, 0, null, 0).status() == Status.OK);
        check("pendências = alerta", SaudeAvaliacao.tef("clisitef", "clisitef", true, 2, 0, null, 0).status() == Status.ALERTA);
        check("cartão sem venda = alerta", SaudeAvaliacao.tef("clisitef", "clisitef", true, 0, 1, null, 0).status() == Status.ALERTA);
        check("tudo bem = ok", SaudeAvaliacao.tef("clisitef", "clisitef", true, 0, 0, null, 0).status() == Status.OK);
        check("simulador avisa que não cobra", SaudeAvaliacao.tef("simulador", "simulador", true, 0, 0, null, 0).detalhe().contains("não cobra"));
        // PIX
        check("pix config incompleta = falha", SaudeAvaliacao.pix("ASAAS", false, List.of("falta PIX_ASAAS_API_KEY"), null, false, 0, 0).status() == Status.FALHA);
        check("config incompleta tem prioridade", SaudeAvaliacao.pix("ASAAS", false, List.of("x"), "erro", false, 9, 0).detalhe().startsWith("Configuração incompleta"));
        check("pix erro de conexão = falha", SaudeAvaliacao.pix("ASAAS", false, List.of(), "HTTP 401", false, 0, 0).status() == Status.FALHA);
        check("pix sem webhook = alerta", SaudeAvaliacao.pix("ASAAS", false, List.of(), null, true, 0, 0).status() == Status.ALERTA);
        check("pix sem venda = alerta", SaudeAvaliacao.pix("ASAAS", false, List.of(), null, false, 2, 0).status() == Status.ALERTA);
        check("pix ok", SaudeAvaliacao.pix("ASAAS", false, List.of(), null, false, 0, 0).status() == Status.OK);
        check("pix simulador ok", SaudeAvaliacao.pix("SIMULADOR", true, List.of(), null, false, 0, 0).detalhe().contains("simulador"));
        // geral
        Item ok1 = new Item("PIX", "x", Status.OK, "", 0), al = new Item("TEF", "x", Status.ALERTA, "", 0), fa = new Item("TEF", "x", Status.FALHA, "", 0), nc = new Item("TEF", null, Status.NAO_CONFIGURADO, "", 0);
        check("geral ok", SaudeAvaliacao.geral(List.of(ok1, nc)) == Status.OK);
        check("geral só não configurado = ok", SaudeAvaliacao.geral(List.of(nc)) == Status.OK);
        check("geral alerta", SaudeAvaliacao.geral(List.of(ok1, al)) == Status.ALERTA);
        check("geral falha vence", SaudeAvaliacao.geral(List.of(al, fa, ok1)) == Status.FALHA);
        // limpeza de mensagens
        check("limpa null", SaudeAvaliacao.limpar(null).equals("erro desconhecido"));
        check("limpa quebra de linha", !SaudeAvaliacao.limpar("a\nb\r\nc").contains("\n"));
        check("limpa bearer", !SaudeAvaliacao.limpar("falhou Authorization: Bearer abc123.def456").contains("abc123"));
        check("limpa token json", !SaudeAvaliacao.limpar("{\"access_token\":\"SEGREDOSEGREDO\"}").contains("SEGREDOSEGREDO"));
        check("limita tamanho", SaudeAvaliacao.limpar("x".repeat(1000)).length() <= 301);
        System.out.println((falhas == 0 ? "OK" : "COM FALHAS") + " — " + ok + " verificações passaram, " + falhas + " falharam");
        if (falhas > 0) System.exit(1);
    }
}
