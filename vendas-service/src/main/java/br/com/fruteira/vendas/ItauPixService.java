package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;

/** Pix via Itaú (API Pix padrão Bacen). Configuração: PIX_ITAU_* (grupo pix.itau). */
@ApplicationScoped
public class ItauPixService extends BacenPixService {
    @Override public PixProvider provider() { return PixProvider.ITAU; }
    @Override protected PixConfig.Psp psp() { return config.itau(); }
}
