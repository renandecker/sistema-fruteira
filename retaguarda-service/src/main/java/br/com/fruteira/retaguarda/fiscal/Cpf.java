package br.com.fruteira.retaguarda.fiscal;

/** Validação de CPF (dígitos verificadores, módulo 11). Sem dependências: usada também pelo vendas-service (cópia). */
public final class Cpf {
    private Cpf() {}

    /** Mantém só os dígitos. null/vazio → "" (venda sem CPF é permitida). */
    public static String somenteDigitos(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** true se tem 11 dígitos, não são todos iguais e os dois dígitos verificadores conferem. */
    public static boolean valido(String s) {
        String c = somenteDigitos(s);
        if (c.length() != 11 || c.chars().distinct().count() == 1) return false;
        return dv(c, 9) == c.charAt(9) - '0' && dv(c, 10) == c.charAt(10) - '0';
    }

    private static int dv(String c, int n) {
        int soma = 0;
        for (int i = 0; i < n; i++) soma += (c.charAt(i) - '0') * (n + 1 - i);
        int r = soma % 11;
        return r < 2 ? 0 : 11 - r;
    }

    /** 123.456.789-09 (se não tiver 11 dígitos devolve só os dígitos). */
    public static String formatar(String s) {
        String c = somenteDigitos(s);
        return c.length() != 11 ? c : c.substring(0, 3) + "." + c.substring(3, 6) + "." + c.substring(6, 9) + "-" + c.substring(9);
    }

    /**
     * Normaliza o CPF informado no caixa: vazio → null (venda anônima); inválido → IllegalArgumentException("CPF inválido").
     */
    public static String normalizarOuNulo(String s) {
        String c = somenteDigitos(s);
        if (c.isEmpty()) return null;
        if (!valido(c)) throw new IllegalArgumentException("CPF inválido");
        return c;
    }
}
