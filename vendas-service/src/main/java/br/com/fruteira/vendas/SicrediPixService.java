package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;

/** Pix via Sicredi (API Pix padrão Bacen). Configuração: PIX_SICREDI_* (grupo pix.sicredi). */
@ApplicationScoped
public class SicrediPixService extends BacenPixService {
    @Override public PixProvider provider() { return PixProvider.SICREDI; }
    @Override protected PixConfig.Psp psp() { return config.sicredi(); }
    /** Caminhos da API do Sicredi ficam em /api/v2 (confirme na documentação; PIX_SICREDI_PREFIXO_API sobrescreve). */
    @Override protected String prefixoApiPadrao() { return "/api/v2"; }
}
