package br.com.fruteira.retaguarda.fiscal;

/** Validação de documentos e dados cadastrais obrigatórios na nota (CNPJ, Inscrição Estadual do RS, CEP, código IBGE). Só JDK. */
public final class Cadastro {
    private Cadastro() {}

    public static String digitos(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    /** CNPJ: 14 dígitos, não todos iguais, dígitos verificadores (módulo 11, pesos 5-4-3-2-9-8-7-6-5-4-3-2 e 6-5-4-3-2-9-8-7-6-5-4-3-2). */
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

    /** CPF (11) ou CNPJ (14) válido. */
    public static boolean cpfOuCnpjValido(String s) { String d = digitos(s); return d.length() == 11 ? Cpf.valido(d) : d.length() == 14 && cnpjValido(d); }

    /** "CPF" | "CNPJ" | null (vazio ou tamanho que não é nenhum dos dois). */
    public static String tipoDocumento(String s) { String d = digitos(s); return d.length() == 11 ? "CPF" : d.length() == 14 ? "CNPJ" : null; }

    public static String formatar(String s) {
        String d = digitos(s);
        if (d.length() == 11) return Cpf.formatar(d);
        if (d.length() == 14) return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/" + d.substring(8, 12) + "-" + d.substring(12);
        return d;
    }

    /** IE do RS: 10 dígitos; o último é o DV (módulo 11, pesos 2-9-8-7-6-5-4-3-2). Outras UFs: só confere que são 2 a 14 dígitos. */
    public static boolean ieValida(String ie, String uf) {
        String d = digitos(ie);
        if (d.length() < 2 || d.length() > 14) return false;
        if (!"RS".equalsIgnoreCase(uf)) return true;
        if (d.length() != 10) return false;
        int[] peso = {2, 9, 8, 7, 6, 5, 4, 3, 2}; int soma = 0;
        for (int i = 0; i < 9; i++) soma += (d.charAt(i) - '0') * peso[i];
        int dv = 11 - soma % 11; if (dv >= 10) dv = 0;
        return dv == d.charAt(9) - '0';
    }

    public static boolean cepValido(String s) { return digitos(s).length() == 8; }
    /** Código IBGE do município: 7 dígitos, começando pelo código da UF (RS = 43). */
    public static boolean codigoMunicipioValido(String cMun, int cUF) { String d = digitos(cMun); return d.length() == 7 && d.startsWith(String.valueOf(cUF)); }
}
