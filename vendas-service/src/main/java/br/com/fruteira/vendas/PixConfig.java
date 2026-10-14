package br.com.fruteira.vendas;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Configuração centralizada do Pix (prefixo "pix"). Valores vêm do application.properties, que lê as variáveis de ambiente do .env
 * (PIX_PROVIDER, PIX_CHAVE, PIX_EFI_CLIENT_ID, ...). Campos que podem ficar vazios são Optional: String vazia em @ConfigProperty/mapping
 * derruba a partida (SRCFG00040).
 * Parte comum no topo; abaixo, um grupo por provedor: efi/itau/bradesco/sicoob/sicredi (API padrão Bacen) e mercado-pago/asaas (API própria).
 */
@ConfigMapping(prefix = "pix")
public interface PixConfig {
    /** Provedor ativo: veja {@link PixProvider#deTexto(String)}. */
    @WithDefault("simulador") String provider();
    /** Chave Pix do recebedor (cadastrada no provedor). */
    Optional<String> chave();
    /** Nome e cidade do recebedor (BR Code do simulador). */
    @WithDefault("FRUTEIRA CONVENTOS") String nome();
    @WithDefault("CIDADE") String cidade();
    /** Validade da cobrança, em segundos. */
    @WithDefault("300") int expiracaoSeg();

    Webhook webhook();
    Simulador simulador();

    Psp efi();
    Psp itau();
    Psp bradesco();
    Psp sicoob();
    Psp sicredi();
    MercadoPago mercadoPago();
    Asaas asaas();

    interface Webhook {
        /** Segredo do webhook público (cabeçalho X-Webhook-Token ou ?token=). Obrigatório fora do simulador. */
        Optional<String> segredo();
        /** URL pública que o gerente registra no provedor. */
        Optional<String> url();
    }
    interface Simulador {
        /** Segundos até o "cliente" pagar sozinho; 0 = só pelo botão. */
        @WithDefault("6") int pagarEmSeg();
    }
    /** Provedor que fala a API Pix padrão do Banco Central: OAuth2 + mTLS (.p12). Variáveis PIX_<BANCO>_*. */
    interface Psp {
        /** Vazio = sugestão do provedor, se houver ({@link PixProvider#urlPadrao()}). */
        Optional<String> baseUrl();
        /** Vazio = padrão do provedor ({@link PixProvider#tokenUrlPadrao()}) ou {baseUrl}/oauth/token. */
        Optional<String> tokenUrl();
        Optional<String> clientId();
        Optional<String> clientSecret();
        /** basic-json | basic-form | form. Vazio = padrão do provedor ({@link PixProvider#formatoTokenPadrao()}). */
        Optional<String> tokenFormato();
        /** Escopos OAuth2 separados por espaço. Vazio = padrão do banco. */
        Optional<String> escopos();
        /** Cabeçalhos extras em todas as chamadas, "Nome=valor;Outro=valor" (ex.: chave de API do banco). */
        Optional<String> cabecalhos();
        /** Prefixo da versão da API ({prefixo}/cob, {prefixo}/pix/...). Vazio = padrão do banco (/v2; Sicoob = nenhum; Sicredi = /api/v2). "-" = nenhum. */
        Optional<String> prefixoApi();
        /** Recurso da cobrança. Vazio = /cob (alguns bancos usam /cob-emv). */
        Optional<String> caminhoCob();
        /** Chave Pix própria deste banco. Vazio = usa PIX_CHAVE. */
        Optional<String> chave();
        /** Caminho do certificado .p12 (mTLS). Se o banco entregou .pem + .key, converta (veja o README). */
        Optional<String> certificado();
        Optional<String> certificadoSenha();
        /** Envia x-skip-mtls-checking ao registrar o webhook (Efí). */
        @WithDefault("false") boolean skipMtlsWebhook();
    }
    /** Mercado Pago: API própria (/v1/payments). Variáveis PIX_MERCADO_PAGO_*. */
    interface MercadoPago {
        /** Vazio = https://api.mercadopago.com */
        Optional<String> baseUrl();
        /** Access Token da aplicação (Credenciais de produção ou de teste). */
        Optional<String> accessToken();
        /** E-mail do pagador exigido pela API (um e-mail genérico da loja serve). */
        Optional<String> emailPagador();
        /** CPF do pagador, se a conta exigir identificação no Pix (opcional). */
        Optional<String> cpfPagador();
    }
    /** Asaas: API própria (/v3). Variáveis PIX_ASAAS_*. */
    interface Asaas {
        /** Vazio = https://api.asaas.com/v3 (sandbox: https://api-sandbox.asaas.com/v3). */
        Optional<String> baseUrl();
        /** Chave de API (cabeçalho access_token). */
        Optional<String> apiKey();
        /** Id do cliente "Consumidor final" (cus_...) criado uma vez no Asaas; toda cobrança é feita nele. */
        Optional<String> clienteId();
        /** E-mail de aviso exigido ao registrar o webhook pela API. */
        Optional<String> emailWebhook();
    }
}
