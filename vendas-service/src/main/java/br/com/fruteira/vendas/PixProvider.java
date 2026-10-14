package br.com.fruteira.vendas;

import java.util.Arrays;

/**
 * Provedores de Pix suportados. Selecione com PIX_PROVIDER (EFI, ITAU, ...; maiúsculas/minúsculas e "-" ou "_" tanto faz).
 * SIMULADOR é só para desenvolvimento e ensaio: não movimenta dinheiro.
 * Cada provedor é atendido por um {@link PixProviderService}; os "padrão Bacen" (API Pix /v2/cob) compartilham {@link BacenPixService}.
 */
public enum PixProvider {
    SIMULADOR("Simulador (não cobra de verdade)", false, null, null, null),
    EFI("Efí", true, "basic-json", "https://pix.api.efipay.com.br", null),                  // sandbox: https://pix-h.api.efipay.com.br
    MERCADO_PAGO("Mercado Pago", false, null, "https://api.mercadopago.com", null),         // API própria (/v1/payments), não é o padrão Bacen
    ASAAS("Asaas", false, null, "https://api.asaas.com/v3", null),                         // API própria (/v3); sandbox: https://api-sandbox.asaas.com/v3
    ITAU("Itaú", true, "form", null, "https://sts.itau.com.br/api/oauth/token"),
    BRADESCO("Bradesco", true, "basic-form", "https://qrpix.bradesco.com.br", "https://qrpix.bradesco.com.br/auth/server/oauth/token"),
    SICOOB("Sicoob", true, "form", "https://api.sicoob.com.br/pix/api/v2", "https://auth.sicoob.com.br/auth/realms/cooperado/protocol/openid-connect/token"),
    SICREDI("Sicredi", true, "basic-form", "https://api-pix.sicredi.com.br", null);        // sandbox: https://api-pix-h.sicredi.com.br

    private final String rotulo; private final boolean padraoBacen; private final String formatoTokenPadrao; private final String urlPadrao; private final String tokenUrlPadrao;
    PixProvider(String rotulo, boolean padraoBacen, String formatoTokenPadrao, String urlPadrao, String tokenUrlPadrao) {
        this.rotulo = rotulo; this.padraoBacen = padraoBacen; this.formatoTokenPadrao = formatoTokenPadrao; this.urlPadrao = urlPadrao; this.tokenUrlPadrao = tokenUrlPadrao;
    }
    /** Nome para exibir. */
    public String rotulo() { return rotulo; }
    /** true = fala a API Pix padrão do Banco Central (PUT /v2/cob/{txid}, OAuth2 e mTLS). */
    public boolean padraoBacen() { return padraoBacen; }
    /** Formato do pedido de token: basic-json | basic-form | form. Confirme na documentação do PSP; PIX_<PROVEDOR>_TOKEN_FORMATO sobrescreve. */
    public String formatoTokenPadrao() { return formatoTokenPadrao; }
    /** URL base de produção sugerida (null = informe PIX_<PROVEDOR>_URL). Confirme na documentação do provedor; PIX_<PROVEDOR>_URL sobrescreve. */
    public String urlPadrao() { return urlPadrao; }
    /** URL do token quando difere de {base}/oauth/token (null = usa {base}/oauth/token). PIX_<PROVEDOR>_TOKEN_URL sobrescreve. */
    public String tokenUrlPadrao() { return tokenUrlPadrao; }
    /** Prefixo das variáveis de ambiente do provedor, ex.: PIX_EFI_ (PIX_MERCADO_PAGO_...). */
    public String prefixoEnv() { return "PIX_" + name() + "_"; }

    /** Lê o valor de PIX_PROVIDER: ignora caixa e aceita "mercado-pago", "Mercado Pago" ou "MERCADO_PAGO". Vazio = SIMULADOR. */
    public static PixProvider deTexto(String texto) {
        if (texto == null || texto.isBlank()) return SIMULADOR;
        String n = texto.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        if (n.equals("PSP")) throw new IllegalArgumentException("PIX_PROVIDER=psp não existe mais: informe o provedor (" + opcoes() + ") e use as variáveis PIX_<PROVEDOR>_*");
        for (PixProvider p : values()) if (p.name().equals(n)) return p;
        throw new IllegalArgumentException("Provedor Pix desconhecido: \"" + texto + "\". Use um de: " + opcoes());
    }
    public static String opcoes() { return String.join(", ", Arrays.stream(values()).map(Enum::name).toList()); }
}
