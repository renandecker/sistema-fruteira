package br.com.fruteira.vendas;

import java.util.Map;
import java.util.Set;

/** Catálogo de cartões aceitos. Mantenha em sincronia com pdv-web/src/cartoes.js. */
public final class Cartoes {
    private Cartoes() {}
    private static final Set<String> AL = Set.of("VALE_ALIMENTACAO"), RF = Set.of("VALE_REFEICAO"), AMBOS = Set.of("VALE_ALIMENTACAO", "VALE_REFEICAO"), BANCARIO = Set.of("CREDITO", "DEBITO");
    /** id da operadora → meios que ela paga */
    public static final Map<String, Set<String>> CATALOGO = Map.ofEntries(
        Map.entry("ALELO_ALIMENTACAO", AL), Map.entry("ALELO_REFEICAO", RF), Map.entry("ALELO_TUDO", AMBOS),
        Map.entry("BANRICARD", AMBOS), Map.entry("BANRICOMPRAS", BANCARIO), Map.entry("TICKET", AMBOS),
        Map.entry("PLUXEE", AMBOS), Map.entry("VR", AMBOS), Map.entry("GREENCARD", AMBOS), Map.entry("VALECARD_VOLUS", AMBOS));
    public static boolean beneficio(String meio) { return "VALE_ALIMENTACAO".equals(meio) || "VALE_REFEICAO".equals(meio); }
    public static boolean aceita(String operadora, String meio) { return operadora != null && CATALOGO.getOrDefault(operadora, Set.of()).contains(meio); }
}
