package br.com.fruteira.tef;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Banco do Brasil — veja AdquirenteReservado: seleção por caixa e variáveis prontas; a comunicação com a maquininha ainda não existe. Usa BB_EC/BB_TERMINAL (se vazios, CIELO_EC/CIELO_TERMINAL). */
@ApplicationScoped
public class BancoBrasilProvider extends AdquirenteReservado {
    @ConfigProperty(name = "tef.bb.ec", defaultValue = "") String ec;
    @ConfigProperty(name = "tef.bb.terminal", defaultValue = "") String terminal;

    public BancoBrasilProvider() { super("bb", "Banco do Brasil"); }
    @Override List<String[]> obrigatorias() { return List.of(new String[] {"BB_EC", ec}, new String[] {"BB_TERMINAL", terminal}); }
}
