package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;

/** Pix via Bradesco (API Pix padrão Bacen). Configuração: PIX_BRADESCO_* (grupo pix.bradesco). */
@ApplicationScoped
public class BradescoPixService extends BacenPixService {
    @Override public PixProvider provider() { return PixProvider.BRADESCO; }
    @Override protected PixConfig.Psp psp() { return config.bradesco(); }
}
