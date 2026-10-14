package br.com.fruteira.vendas;

import java.util.LinkedHashMap;
import java.util.Map;

/** Utilidades do Pix só com o JDK (testáveis sem Maven). */
public final class PixUtil {
    private PixUtil() {}

    /**
     * Lê cabeçalhos extras de uma variável de ambiente: "Nome=valor;Outro-Nome=valor2". O valor pode ter "=" (só o primeiro separa).
     * Itens sem nome ou sem "=" são ignorados.
     */
    public static Map<String, String> parseCabecalhos(String texto) {
        Map<String, String> m = new LinkedHashMap<>();
        if (texto == null || texto.isBlank()) return m;
        for (String par : texto.split(";")) {
            int i = par.indexOf('=');
            if (i <= 0) continue;
            String nome = par.substring(0, i).trim(), valor = par.substring(i + 1).trim();
            if (!nome.isEmpty()) m.put(nome, valor);
        }
        return m;
    }
}
