package br.com.fruteira.tef;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Stone — veja AdquirenteReservado: seleção por caixa e variáveis prontas; a comunicação com a maquininha ainda não existe. */
@ApplicationScoped
public class StoneProvider extends AdquirenteReservado {
    @ConfigProperty(name = "tef.stone.codigo", defaultValue = "") String codigo;
    @ConfigProperty(name = "tef.stone.terminal-serial", defaultValue = "") String serial;

    public StoneProvider() { super("stone", "Stone"); }
    @Override List<String[]> obrigatorias() { return List.of(new String[] {"STONE_CODIGO", codigo}, new String[] {"STONE_TERMINAL_SERIAL", serial}); }
}
