package br.com.fruteira.retaguarda.fiscal;

/**
 * Documentos e códigos do cadastro fiscal (só JDK): CPF (em {@link Cpf}), CNPJ, Inscrição Estadual do RS, CEP e código IBGE do município.
 * A mesma regra existe no front (pdv-web/src/fiscal.js).
 */
public final class Documento {
    private Documento() {}

    public static String digitos(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** 14 dígitos, não todos iguais, dígitos verificadores (pesos 5..2,9..2 e 6..2,9..2). */
    public static boolean cnpjValido(String s) {
        String c = digitos(s);
        if (c.length() != 14 || c.chars().distinct().count() == 1) return false;
        return dvCnpj(c, 12) == c.charAt(12) - '0' && dvCnpj(c, 13) == c.charAt(13) - '0';
    }
    private static int dvCnpj(String c, int n) {
        int soma = 0, peso = n - 7;
        for (int i = 0; i < n; i++) { soma += (c.charAt(i) - '0') * peso; peso = peso == 2 ? 9 : peso - 1; }
        int r = soma % 11; return r < 2 ? 0 : 11 - r;
    }

    /** CPF (11 dígitos) ou CNPJ (14 dígitos) válido. */
    public static boolean valido(String s) { String c = digitos(s); return c.length() == 11 ? Cpf.valido(c) : c.length() == 14 && cnpjValido(c); }

    /** Vazio → null (sem identificação); CPF/CNPJ válido → só dígitos; inválido → IllegalArgumentException("CPF/CNPJ inválido"). */
    public static String normalizarOuNulo(String s) {
        String c = digitos(s);
        if (c.isEmpty()) return null;
        if (!valido(c)) throw new IllegalArgumentException(c.length() == 14 ? "CNPJ inválido" : "CPF inválido");
        return c;
    }

    public static String formatar(String s) {
        String c = digitos(s);
        if (c.length() == 11) return Cpf.formatar(c);
        return c.length() != 14 ? c : c.substring(0, 2) + "." + c.substring(2, 5) + "." + c.substring(5, 8) + "/" + c.substring(8, 12) + "-" + c.substring(12);
    }

    /** Inscrição Estadual do RS: 10 dígitos (NNN/NNNNNNN), 3 primeiros = município (001 a 467), DV módulo 11 com pesos 2,9,8,7,6,5,4,3,2. */
    public static boolean ieRsValida(String s) {
        String c = digitos(s);
        if (c.length() != 10) return false;
        int mun = Integer.parseInt(c.substring(0, 3));
        if (mun < 1 || mun > 467) return false;
        int[] pesos = {2, 9, 8, 7, 6, 5, 4, 3, 2}; int soma = 0;
        for (int i = 0; i < 9; i++) soma += (c.charAt(i) - '0') * pesos[i];
        int dv = 11 - soma % 11; if (dv >= 10) dv = 0;
        return dv == c.charAt(9) - '0';
    }
    /** IE de outra UF: só confere que há 2 a 14 dígitos (cada estado tem sua regra). "ISENTO" também é aceito para quem não é contribuinte. */
    public static boolean ieGenericaValida(String s) {
        if (s != null && s.trim().equalsIgnoreCase("ISENTO")) return true;
        int n = digitos(s).length(); return n >= 2 && n <= 14;
    }
    public static boolean ieValida(String s, String uf) { return "RS".equalsIgnoreCase(uf == null || uf.isBlank() ? "RS" : uf.trim()) ? ieRsValida(s) : ieGenericaValida(s); }

    public static boolean cepValido(String s) { String c = digitos(s); return c.length() == 8 && !c.equals("00000000"); }

    /** Código IBGE do município: 7 dígitos, os 2 primeiros são a UF e o último é o dígito verificador (pesos 1,2,1,2,1,2 sobre os 6 primeiros). */
    public static boolean cMunValido(String s) {
        String c = digitos(s);
        if (c.length() != 7) return false;
        int soma = 0;
        for (int i = 0; i < 6; i++) { int p = (c.charAt(i) - '0') * (i % 2 == 0 ? 1 : 2); soma += p / 10 + p % 10; }
        return (10 - soma % 10) % 10 == c.charAt(6) - '0';
    }

    private static final java.util.Set<String> UFS = java.util.Set.of("AC","AL","AP","AM","BA","CE","DF","ES","GO","MA","MT","MS","MG","PA","PB","PR","PE","PI","RJ","RN","RS","RO","RR","SC","SP","SE","TO");
    public static boolean ufValida(String s) { return s != null && UFS.contains(s.trim().toUpperCase()); }
}
