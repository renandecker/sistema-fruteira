package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.fiscal.Cadastro;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;

/** Conferência dos dados cadastrais (documento, inscrições, endereço) de clientes e fornecedores: o que vai na nota precisa estar certo desde o cadastro. */
final class CadastroFiscal {
    private CadastroFiscal() {}
    private static boolean vazio(String s) { return s == null || s.isBlank(); }
    static String vz(String s) { return vazio(s) ? null : s.trim(); }

    /** Endereço: opcional, mas se começar a ser preenchido tem de ficar completo (logradouro, número, bairro, município, código IBGE, UF e CEP). */
    static List<String> endereco(String logradouro, String numero, String bairro, String municipio, String cMun, String uf, String cep) {
        List<String> faltam = new ArrayList<>();
        boolean algum = !(vazio(logradouro) && vazio(numero) && vazio(bairro) && vazio(municipio) && vazio(cMun) && vazio(uf) && vazio(cep));
        if (!algum) return faltam;
        if (vazio(logradouro)) faltam.add("logradouro");
        if (vazio(numero)) faltam.add("número (S/N se não houver)");
        if (vazio(bairro)) faltam.add("bairro");
        if (vazio(municipio)) faltam.add("município");
        if (vazio(uf) || !uf.trim().matches("[A-Za-z]{2}")) faltam.add("UF (2 letras)");
        if (vazio(cMun) || !Cadastro.digitos(cMun).matches("\\d{7}")) faltam.add("código IBGE do município (7 dígitos)");
        else if (!vazio(uf) && uf.trim().matches("[A-Za-z]{2}") && "RS".equalsIgnoreCase(uf.trim()) && !Cadastro.digitos(cMun).startsWith("43")) faltam.add("código IBGE de município do RS (começa por 43)");
        if (!Cadastro.cepValido(cep)) faltam.add("CEP (8 dígitos)");
        return faltam;
    }
    static void exigirEndereco(String titulo, String logradouro, String numero, String bairro, String municipio, String cMun, String uf, String cep) {
        List<String> f = endereco(logradouro, numero, bairro, municipio, cMun, uf, cep);
        if (!f.isEmpty()) throw new WebApplicationException(titulo + ": endereço incompleto, falta " + String.join(", ", f), 422);
    }
    static void exigirIe(String ie, String uf) {
        if (vazio(ie)) return;
        String t = ie.trim();
        if (t.equalsIgnoreCase("ISENTO")) return;
        if (!Cadastro.ieValida(t, vazio(uf) ? "" : uf.trim())) throw new WebApplicationException("Inscrição Estadual inválida" + ("RS".equalsIgnoreCase(uf) ? " (RS: 10 dígitos com dígito verificador)" : "") + " — ou escreva ISENTO", 422);
    }
}
