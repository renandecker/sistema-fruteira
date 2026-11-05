package br.com.fruteira.tef;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Cielo — veja AdquirenteReservado: seleção por caixa e variáveis prontas; a comunicação com a maquininha ainda não existe. */
@ApplicationScoped
public class CieloProvider extends AdquirenteReservado {
    @ConfigProperty(name = "tef.cielo.ec", defaultValue = "") String ec;
    @ConfigProperty(name = "tef.cielo.terminal", defaultValue = "") String terminal;

    public CieloProvider() { super("cielo", "Cielo"); }
    @Override List<String[]> obrigatorias() { return List.of(new String[] {"CIELO_EC", ec}, new String[] {"CIELO_TERMINAL", terminal}); }
}
