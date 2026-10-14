package br.com.fruteira.vendas;

/** Teste só com o JDK (sem Maven): ./testar-pix.sh */
public class PixEnumsTeste {
    static int ok = 0, falhas = 0;
    static void confere(String nome, boolean cond) { if (cond) ok++; else { falhas++; System.out.println("FALHOU: " + nome); } }
    static boolean erro(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }

    public static void main(String[] a) {
        // PixProvider: os sete provedores pedidos + SIMULADOR para desenvolvimento
        for (String n : new String[] { "EFI", "MERCADO_PAGO", "ASAAS", "ITAU", "BRADESCO", "SICOOB", "SICREDI", "SIMULADOR" })
            confere("existe " + n, PixProvider.valueOf(n) != null);
        confere("8 valores", PixProvider.values().length == 8);
        confere("vazio = simulador", PixProvider.deTexto(null) == PixProvider.SIMULADOR && PixProvider.deTexto("  ") == PixProvider.SIMULADOR);
        confere("minúsculas", PixProvider.deTexto("efi") == PixProvider.EFI && PixProvider.deTexto("Itau") == PixProvider.ITAU);
        confere("hífen e espaço", PixProvider.deTexto("mercado-pago") == PixProvider.MERCADO_PAGO && PixProvider.deTexto("Mercado Pago") == PixProvider.MERCADO_PAGO);
        confere("psp antigo é recusado", erro(() -> PixProvider.deTexto("psp")));
        confere("desconhecido é recusado", erro(() -> PixProvider.deTexto("pagseguro")));
        confere("padrão Bacen", PixProvider.EFI.padraoBacen() && PixProvider.SICREDI.padraoBacen() && !PixProvider.MERCADO_PAGO.padraoBacen() && !PixProvider.ASAAS.padraoBacen());
        confere("prefixo env", PixProvider.EFI.prefixoEnv().equals("PIX_EFI_") && PixProvider.MERCADO_PAGO.prefixoEnv().equals("PIX_MERCADO_PAGO_"));
        confere("url padrão só onde conhecida", PixProvider.EFI.urlPadrao() != null && PixProvider.ITAU.urlPadrao() == null);
        for (PixProvider p : PixProvider.values()) if (p.padraoBacen()) confere("formato de token de " + p, p.formatoTokenPadrao() != null);

        // URLs/token padrão conhecidos
        confere("Sicoob token", PixProvider.SICOOB.tokenUrlPadrao().equals("https://auth.sicoob.com.br/auth/realms/cooperado/protocol/openid-connect/token"));
        confere("Bradesco token", PixProvider.BRADESCO.tokenUrlPadrao().equals("https://qrpix.bradesco.com.br/auth/server/oauth/token"));
        confere("Efí sem token próprio (usa /oauth/token)", PixProvider.EFI.tokenUrlPadrao() == null);
        confere("Asaas com /v3", PixProvider.ASAAS.urlPadrao().endsWith("/v3"));
        confere("MP url", PixProvider.MERCADO_PAGO.urlPadrao().equals("https://api.mercadopago.com"));

        // PixUtil.parseCabecalhos
        var h = PixUtil.parseCabecalhos("x-itau-apikey=abc123; X-Outro = a=b ;semigual;=semnome;;");
        confere("cabeçalhos: 2 válidos", h.size() == 2);
        confere("cabeçalho simples", "abc123".equals(h.get("x-itau-apikey")));
        confere("valor com '='", "a=b".equals(h.get("X-Outro")));
        confere("vazio/null", PixUtil.parseCabecalhos(null).isEmpty() && PixUtil.parseCabecalhos("  ").isEmpty());

        // PixStatus
        confere("4 estados", PixStatus.values().length == 4);
        confere("ordem", PixStatus.valueOf("REMOVIDA_PELO_USUARIO_RECEBEDOR") == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR);
        confere("texto Bacen", PixStatus.deTexto("CONCLUIDA") == PixStatus.CONCLUIDA && PixStatus.deTexto("expirada") == PixStatus.EXPIRADA);
        confere("removida pelo PSP vira removida", PixStatus.deTexto("REMOVIDA_PELO_PSP") == PixStatus.REMOVIDA_PELO_USUARIO_RECEBEDOR);
        confere("desconhecido/null = ATIVA", PixStatus.deTexto("xyz") == PixStatus.ATIVA && PixStatus.deTexto(null) == PixStatus.ATIVA);
        confere("encerrada", !PixStatus.ATIVA.encerrada() && PixStatus.CONCLUIDA.encerrada() && PixStatus.EXPIRADA.encerrada());

        System.out.println(ok + " ok, " + falhas + " falha(s)");
        if (falhas > 0) System.exit(1);
    }
}
