package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * Pix via Sicoob (API Pix padrão Bacen). Configuração: PIX_SICOOB_* (grupo pix.sicoob).
 * Particularidades: o Client ID também vai no cabeçalho "client_id" (token e demais chamadas) e há escopos extras.
 */
@ApplicationScoped
public class SicoobPixService extends BacenPixService {
    @Override public PixProvider provider() { return PixProvider.SICOOB; }
    @Override protected PixConfig.Psp psp() { return config.sicoob(); }
    /** A URL base do Sicoob já termina em /v2 (…/pix/api/v2): sem prefixo extra. */
    @Override protected String prefixoApiPadrao() { return ""; }
    @Override protected Map<String, String> cabecalhosDoBanco() { return clientId().isBlank() ? Map.of() : Map.of("client_id", clientId()); }
    @Override protected String escoposPadrao() {
        return "cob.write cob.read cobv.write cobv.read lotecobv.write lotecobv.read pix.write pix.read webhook.write webhook.read payloadlocation.write payloadlocation.read";
    }
}
