package br.com.fruteira.vendas;

import jakarta.enterprise.context.ApplicationScoped;

/** Pix via Efí (API Pix padrão Bacen). Configuração: PIX_EFI_* (grupo pix.efi). */
@ApplicationScoped
public class EfiPixService extends BacenPixService {
    @Override public PixProvider provider() { return PixProvider.EFI; }
    @Override protected PixConfig.Psp psp() { return config.efi(); }
}
