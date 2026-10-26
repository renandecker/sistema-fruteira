package br.com.fruteira.retaguarda.fiscal;

import java.math.BigDecimal;
import java.util.*;

/**
 * Conferência dos dados obrigatórios ANTES de montar/assinar/transmitir a NFC-e: acumula todos os problemas (em vez de parar no primeiro),
 * cada um dizendo o que corrigir e onde. Só JDK. Erro nesses campos vira rejeição da SEFAZ, multa ou retrabalho.
 */
public final class Validador {
    private Validador() {}

    public record Problema(String onde, String mensagem) {
        @Override public String toString() { return onde + ": " + mensagem; }
    }

    static final Set<String> CSOSN = Set.of("102", "103", "300", "400", "500");
    static final Set<String> CST_ICMS = Set.of("00", "40", "41", "50", "60");
    static final Set<String> CST_PIS_COFINS_ALIQ = Set.of("01", "02");
    static final Set<String> CST_PIS_COFINS_NT = Set.of("04", "05", "06", "07", "08", "09");
    static final Set<String> CST_PIS_COFINS_OUTR = Set.of("49", "99");
    static final Set<String> TPAG = Set.of("01", "02", "03", "04", "05", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "90", "99");
    static final Set<String> BANDEIRAS = Set.of("01", "02", "03", "04", "05", "06", "07", "08", "09", "99");

    static boolean vazio(String s) { return s == null || s.isBlank(); }
    private static BigDecimal bd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Cadastro do emitente (a loja): CNPJ, IE, endereço completo, regime e códigos padrão. */
    public static List<Problema> emitente(Config c) {
        List<Problema> p = new ArrayList<>();
        if (!Cadastro.cnpjValido(c.cnpj)) p.add(new Problema("FISCAL_CNPJ", "CNPJ do emitente inválido (14 dígitos com dígitos verificadores corretos)"));
        if (vazio(c.razao)) p.add(new Problema("FISCAL_RAZAO", "razão social obrigatória"));
        if (vazio(c.ie)) p.add(new Problema("FISCAL_IE", "Inscrição Estadual obrigatória na NFC-e"));
        else if (!Cadastro.ieValida(c.ie, c.uf)) p.add(new Problema("FISCAL_IE", "Inscrição Estadual inválida" + ("RS".equalsIgnoreCase(c.uf) ? " (RS: 10 dígitos, último é o dígito verificador)" : "")));
        if (!c.im.isBlank() && Cadastro.digitos(c.im).isEmpty()) p.add(new Problema("FISCAL_IM", "Inscrição Municipal deve ter só números (opcional na NFC-e de mercadoria)"));
        if (vazio(c.logradouro)) p.add(new Problema("FISCAL_LOGRADOURO", "endereço do emitente: logradouro obrigatório"));
        if (vazio(c.numero)) p.add(new Problema("FISCAL_NUMERO", "endereço do emitente: número obrigatório (use S/N se não houver)"));
        if (vazio(c.bairro)) p.add(new Problema("FISCAL_BAIRRO", "endereço do emitente: bairro obrigatório"));
        if (!Cadastro.codigoMunicipioValido(c.cMun, c.cUF)) p.add(new Problema("FISCAL_CMUN", "código IBGE do município deve ter 7 dígitos e começar por " + c.cUF));
        if (vazio(c.municipio)) p.add(new Problema("FISCAL_MUNICIPIO", "endereço do emitente: nome do município obrigatório"));
        if (!Cadastro.cepValido(c.cep)) p.add(new Problema("FISCAL_CEP", "CEP com 8 dígitos obrigatório"));
        if (!Set.of("1", "2", "3", "4").contains(c.crt)) p.add(new Problema("FISCAL_CRT", "regime tributário deve ser 1 (Simples Nacional), 2 (Simples, excesso de sublimite), 3 (Lucro Presumido/Real) ou 4 (MEI)"));
        p.addAll(codigosPadrao(c));
        if (c.usaIbsCbs()) {
            if (!c.cstIbsCbs.matches("\\d{3}")) p.add(new Problema("FISCAL_IBSCBS_CST", "IBS/CBS ligado: informe o CST de 3 dígitos da tabela oficial (ou preencha em cada produto)"));
            if (!c.cClassTrib.matches("\\d{6}")) p.add(new Problema("FISCAL_IBSCBS_CCLASSTRIB", "IBS/CBS ligado: informe o cClassTrib de 6 dígitos da tabela oficial (ou preencha em cada produto)"));
        }
        return p;
    }

    private static List<Problema> codigosPadrao(Config c) {
        List<Problema> p = new ArrayList<>();
        if (!cfopOk(c.cfop)) p.add(new Problema("FISCAL_CFOP", "CFOP da NFC-e (venda a consumidor final dentro do estado) deve ter 4 dígitos e começar por 5 (ex.: 5102)"));
        if (c.simples()) { if (!CSOSN.contains(c.csosn)) p.add(new Problema("FISCAL_CSOSN", "Simples Nacional usa CSOSN " + new TreeSet<>(CSOSN) + " (atual: " + c.csosn + ")")); }
        else if (!CST_ICMS.contains(c.cstIcms)) p.add(new Problema("FISCAL_CST_ICMS", "regime normal usa CST do ICMS " + new TreeSet<>(CST_ICMS) + " (atual: " + c.cstIcms + ")"));
        for (String[] x : new String[][] {{"PIS", c.cstPis}, {"COFINS", c.cstCofins}})
            if (!pisCofinsOk(x[1])) p.add(new Problema("FISCAL_CST_" + x[0], "CST do " + x[0] + " não suportado: " + x[1] + " (aceitos: 01, 02, 04 a 09, 49, 99)"));
        if (!c.origem.matches("[0-8]")) p.add(new Problema("FISCAL_ORIGEM", "origem da mercadoria deve ser de 0 a 8"));
        return p;
    }
    static boolean cfopOk(String s) { return s != null && s.matches("5\\d{3}"); }
    static boolean pisCofinsOk(String s) { return s != null && (CST_PIS_COFINS_ALIQ.contains(s) || CST_PIS_COFINS_NT.contains(s) || CST_PIS_COFINS_OUTR.contains(s)); }

    /** O que vale para um item: a classificação do produto, ou o padrão da loja. */
    public static String cfop(Config c, Dados.Item i) { return vazio(i.cfop()) ? c.cfop : i.cfop().trim(); }
    public static String cstIcms(Config c, Dados.Item i) { return vazio(i.cstIcms()) ? (c.simples() ? c.csosn : c.cstIcms) : i.cstIcms().trim(); }
    public static String cstPis(Config c, Dados.Item i) { return vazio(i.cstPis()) ? c.cstPis : i.cstPis().trim(); }
    public static String cstCofins(Config c, Dados.Item i) { return vazio(i.cstCofins()) ? c.cstCofins : i.cstCofins().trim(); }
    public static String origem(Config c, Dados.Item i) { return vazio(i.origem()) ? c.origem : i.origem().trim(); }
    public static String cstIbsCbs(Config c, Dados.Item i) { return vazio(i.cstIbsCbs()) ? c.cstIbsCbs : i.cstIbsCbs().trim(); }
    public static String cClassTrib(Config c, Dados.Item i) { return vazio(i.cClassTrib()) ? c.cClassTrib : i.cClassTrib().trim(); }
    public static BigDecimal aliqIcms(Config c, Dados.Item i) { return i.aliqIcms() != null ? i.aliqIcms() : c.aliqIcms; }
    public static BigDecimal aliqPis(Config c, Dados.Item i) { return i.aliqPis() != null ? i.aliqPis() : c.aliqPis; }
    public static BigDecimal aliqCofins(Config c, Dados.Item i) { return i.aliqCofins() != null ? i.aliqCofins() : c.aliqCofins; }

    /** Itens, destinatário, tributação e pagamentos de uma venda (junto com {@link #emitente}). */
    public static List<Problema> nota(Config c, Dados d) {
        List<Problema> p = new ArrayList<>();
        if (d.itens() == null || d.itens().isEmpty()) { p.add(new Problema("Itens", "a NFC-e precisa de ao menos um item")); return p; }
        if (d.itens().size() > 990) p.add(new Problema("Itens", "a NFC-e aceita no máximo 990 itens"));
        BigDecimal total = itens(c, d.itens(), "5", p);
        // destinatário (CPF na nota gaúcha ou CNPJ): opcional, mas se vier tem de estar certo
        String doc = Cadastro.digitos(d.cpf());
        if (!doc.isEmpty()) {
            if (doc.length() == 11 && !Cpf.valido(doc)) p.add(new Problema("Destinatário", "CPF inválido"));
            else if (doc.length() == 14 && !Cadastro.cnpjValido(doc)) p.add(new Problema("Destinatário", "CNPJ inválido"));
            else if (doc.length() != 11 && doc.length() != 14) p.add(new Problema("Destinatário", "documento deve ter 11 (CPF) ou 14 (CNPJ) dígitos"));
        }
        // pagamentos
        if (d.pagamentos() == null || d.pagamentos().isEmpty()) { p.add(new Problema("Pagamento", "informe ao menos uma forma de pagamento")); return p; }
        BigDecimal pago = BigDecimal.ZERO, dinheiro = BigDecimal.ZERO; int k = 0;
        for (Dados.Pag g : d.pagamentos()) {
            k++; String onde = "Pagamento " + k;
            if (g.tPag() == null || !TPAG.contains(g.tPag())) p.add(new Problema(onde, "meio de pagamento inválido: " + g.tPag()));
            if (g.valor() == null || g.valor().signum() <= 0) { p.add(new Problema(onde, "valor deve ser maior que zero")); continue; }
            pago = pago.add(g.valor()); if ("01".equals(g.tPag())) dinheiro = dinheiro.add(g.valor());
            if ("03".equals(g.tPag()) || "04".equals(g.tPag())) conferirCartao(c, p, onde, g);
        }
        if (total.signum() > 0) {
            if (pago.compareTo(total) < 0) p.add(new Problema("Pagamento", "pagamentos (R$ " + pago + ") menores que o total da nota (R$ " + total + ")"));
            BigDecimal troco = pago.subtract(total);
            if (troco.signum() > 0 && troco.compareTo(dinheiro) > 0) p.add(new Problema("Pagamento", "só há troco quando parte do pagamento é em dinheiro (sobra de R$ " + troco + ")"));
        }
        return p;
    }

    /** Confere os itens (NCM, unidade, valores, CFOP com o prefixo da operação, ICMS/PIS/COFINS, IBS/CBS) e devolve o total. prefixoCfop: "5" operação interna · "6" interestadual. */
    static BigDecimal itens(Config c, List<Dados.Item> itens, String prefixoCfop, List<Problema> p) {
        BigDecimal total = BigDecimal.ZERO; int n = 0;
        for (Dados.Item i : itens) {
            n++; String onde = "Item " + n + (vazio(i.nome()) ? "" : " (" + i.nome() + ")");
            if (vazio(i.nome())) p.add(new Problema(onde, "descrição obrigatória"));
            if (i.ncm() == null || !i.ncm().matches("\\d{8}")) p.add(new Problema(onde, "NCM de 8 dígitos obrigatório (cadastre em Produtos e preços)"));
            if (vazio(i.unidade()) || i.unidade().trim().length() > 6) p.add(new Problema(onde, "unidade de medida obrigatória (até 6 caracteres: KG, UN, MC, CX)"));
            if (i.qtd() == null || i.qtd().signum() <= 0) p.add(new Problema(onde, "quantidade deve ser maior que zero"));
            if (i.valorUnit() == null || i.valorUnit().signum() <= 0) p.add(new Problema(onde, "valor unitário deve ser maior que zero"));
            if (i.valor() == null || i.valor().signum() <= 0) p.add(new Problema(onde, "valor total deve ser maior que zero"));
            else {
                total = total.add(i.valor());
                if (i.qtd() != null && i.qtd().signum() > 0 && i.valorUnit() != null && i.valorUnit().signum() > 0
                    && i.qtd().multiply(i.valorUnit()).subtract(i.valor()).abs().compareTo(new BigDecimal("0.02")) > 0)
                    p.add(new Problema(onde, "valor total (" + i.valor() + ") não confere com quantidade × valor unitário (" + i.qtd().multiply(i.valorUnit()).setScale(2, java.math.RoundingMode.HALF_UP) + ")"));
            }
            String cf = cfopOp(c, i, prefixoCfop);
            if (!cf.matches(prefixoCfop + "\\d{3}")) p.add(new Problema(onde, "CFOP inválido: " + cf + " (4 dígitos começando por " + prefixoCfop + ("6".equals(prefixoCfop) ? ", operação interestadual" : "") + ")"));
            String ic = cstIcms(c, i);
            if (c.simples()) { if (!CSOSN.contains(ic)) p.add(new Problema(onde, "CSOSN " + ic + " não vale para o Simples Nacional (use " + new TreeSet<>(CSOSN) + ")")); }
            else {
                if (!CST_ICMS.contains(ic)) p.add(new Problema(onde, "CST do ICMS " + ic + " não suportado no regime normal (use " + new TreeSet<>(CST_ICMS) + ")"));
                else if ("00".equals(ic) && aliqIcms(c, i).signum() <= 0) p.add(new Problema(onde, "ICMS tributado (CST 00) exige a alíquota do ICMS"));
            }
            if (!origem(c, i).matches("[0-8]")) p.add(new Problema(onde, "origem da mercadoria deve ser de 0 a 8"));
            conferirPisCofins(p, onde, "PIS", cstPis(c, i), aliqPis(c, i));
            conferirPisCofins(p, onde, "COFINS", cstCofins(c, i), aliqCofins(c, i));
            if (c.usaIbsCbs()) {
                if (!cstIbsCbs(c, i).matches("\\d{3}")) p.add(new Problema(onde, "CST do IBS/CBS (3 dígitos) obrigatório"));
                if (!cClassTrib(c, i).matches("\\d{6}")) p.add(new Problema(onde, "cClassTrib do IBS/CBS (6 dígitos) obrigatório"));
                if (i.pRedIbsCbs() != null && (i.pRedIbsCbs().signum() < 0 || i.pRedIbsCbs().compareTo(new BigDecimal("100")) > 0)) p.add(new Problema(onde, "redução de IBS/CBS deve ficar entre 0 e 100%"));
            }
        }
        return total;
    }
    /** CFOP do item na operação: o do produto (ou o padrão da loja); em venda interestadual o 5xxx vira 6xxx. */
    public static String cfopOp(Config c, Dados.Item i, String prefixo) { String b = cfop(c, i); return "6".equals(prefixo) && b.startsWith("5") ? "6" + b.substring(1) : b; }

    private static void conferirPisCofins(List<Problema> p, String onde, String nome, String cst, BigDecimal aliq) {
        if (!pisCofinsOk(cst)) { p.add(new Problema(onde, "CST do " + nome + " não suportado: " + cst)); return; }
        if (CST_PIS_COFINS_ALIQ.contains(cst) && bd(aliq).signum() <= 0) p.add(new Problema(onde, nome + " tributado (CST " + cst + ") exige a alíquota do " + nome));
    }

    /** Cartão: com TEF integrado a nota leva CNPJ da credenciadora, bandeira e autorização (tpIntegra 1); sem eles a nota sai como cartão não integrado (tpIntegra 2). */
    private static void conferirCartao(Config c, List<Problema> p, String onde, Dados.Pag g) {
        boolean temCnpj = !vazio(g.cnpjCredenciadora());
        if (c.exigeTef) {
            if (!temCnpj) p.add(new Problema(onde, "cartão sem os dados do TEF (CNPJ da credenciadora): a venda precisa ser passada pelo TEF integrado. Se a maquininha não é integrada, desligue FISCAL_EXIGE_TEF"));
            if (vazio(g.tBand())) p.add(new Problema(onde, "cartão sem a bandeira informada pelo TEF"));
            if (vazio(g.cAut())) p.add(new Problema(onde, "cartão sem o número de autorização informado pelo TEF"));
        }
        if (temCnpj && !Cadastro.cnpjValido(g.cnpjCredenciadora())) p.add(new Problema(onde, "CNPJ da credenciadora do cartão inválido: " + g.cnpjCredenciadora()));
        if (!vazio(g.tBand()) && !BANDEIRAS.contains(g.tBand())) p.add(new Problema(onde, "código de bandeira inválido: " + g.tBand() + " (01 a 09 ou 99)"));
    }

    /** NF-e (modelo 55): destinatário completo (empresa), operação, frete/desconto, duplicatas e pagamento. */
    public static List<Problema> nfe(Config c, DadosNfe d) {
        List<Problema> p = new ArrayList<>();
        DadosNfe.Dest t = d.dest();
        if (t == null) { p.add(new Problema("Destinatário", "informe o destinatário da NF-e")); }
        else {
            String doc = Cadastro.digitos(t.doc());
            if (doc.length() == 14 ? !Cadastro.cnpjValido(doc) : doc.length() == 11 ? !Cpf.valido(doc) : true) p.add(new Problema("Destinatário", "CNPJ (ou CPF) válido obrigatório"));
            if (vazio(t.nome()) || t.nome().trim().length() < 2) p.add(new Problema("Destinatário", "razão social/nome obrigatório"));
            if (vazio(t.logradouro()) || vazio(t.numero()) || vazio(t.bairro()) || vazio(t.municipio())) p.add(new Problema("Destinatário", "endereço completo obrigatório (logradouro, número, bairro e município)"));
            if (vazio(t.uf()) || !t.uf().trim().matches("[A-Za-z]{2}")) p.add(new Problema("Destinatário", "UF obrigatória"));
            if (!Cadastro.digitos(t.cMun()).matches("\\d{7}")) p.add(new Problema("Destinatário", "código IBGE do município (7 dígitos) obrigatório"));
            if (!Cadastro.cepValido(t.cep())) p.add(new Problema("Destinatário", "CEP de 8 dígitos obrigatório"));
            int ind = d.indIEDest();
            if (ind == 1 && !Cadastro.ieValida(t.ie(), vazio(t.uf()) ? "" : t.uf().trim())) p.add(new Problema("Destinatário", "Inscrição Estadual inválida"));
            if (ind == 9 && !d.consumidorFinal()) p.add(new Problema("Destinatário", "sem Inscrição Estadual: informe a IE, escreva ISENTO ou marque a venda como consumidor final"));
            if (doc.length() == 11 && !d.consumidorFinal()) p.add(new Problema("Destinatário", "pessoa física compra como consumidor final"));
            if (!vazio(t.email()) && !t.email().trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) p.add(new Problema("Destinatário", "e-mail inválido"));
        }
        if (vazio(d.natOp())) p.add(new Problema("Operação", "natureza da operação obrigatória"));
        if (!Set.of(0, 1, 2, 3, 4, 5, 9).contains(d.indPres())) p.add(new Problema("Operação", "indicador de presença inválido"));
        if (d.itens() == null || d.itens().isEmpty()) { p.add(new Problema("Itens", "a NF-e precisa de ao menos um item")); return p; }
        String prefixo = d.idDest(c.uf) == 2 ? "6" : "5";
        BigDecimal produtos = itens(c, d.itens(), prefixo, p);
        if (d.frete().signum() < 0) p.add(new Problema("Frete", "valor do frete não pode ser negativo"));
        if (!Set.of(0, 1, 2, 3, 4, 9).contains(d.modFrete())) p.add(new Problema("Frete", "modalidade do frete inválida (0 emitente, 1 destinatário, 2 terceiros, 3/4 próprio, 9 sem frete)"));
        else if (d.frete().signum() > 0 && d.modFrete() == 9) p.add(new Problema("Frete", "há valor de frete, mas a modalidade é 9 (sem frete)"));
        if (d.desconto().signum() < 0 || d.desconto().compareTo(produtos) > 0) p.add(new Problema("Desconto", "desconto deve ficar entre 0 e o total dos produtos"));
        BigDecimal vNF = produtos.add(d.frete()).subtract(d.desconto());
        if (d.pagamentos() == null || d.pagamentos().isEmpty()) p.add(new Problema("Pagamento", "informe a forma de pagamento (use 90 = sem pagamento, se for o caso)"));
        else {
            BigDecimal pago = BigDecimal.ZERO; int k = 0;
            for (Dados.Pag g : d.pagamentos()) {
                k++; String onde = "Pagamento " + k;
                if (g.tPag() == null || !TPAG.contains(g.tPag())) p.add(new Problema(onde, "meio de pagamento inválido: " + g.tPag()));
                if (g.valor() == null || g.valor().signum() < 0 || (g.valor().signum() == 0 && !"90".equals(g.tPag()))) p.add(new Problema(onde, "valor deve ser maior que zero"));
                else pago = pago.add(g.valor());
                if (("03".equals(g.tPag()) || "04".equals(g.tPag())) && g.cnpjCredenciadora() != null && !g.cnpjCredenciadora().isBlank() && !Cadastro.cnpjValido(g.cnpjCredenciadora())) p.add(new Problema(onde, "CNPJ da credenciadora inválido"));
            }
            if (vNF.signum() > 0 && pago.compareTo(vNF) != 0 && !d.pagamentos().stream().allMatch(g -> "90".equals(g.tPag()))) p.add(new Problema("Pagamento", "a soma dos pagamentos (R$ " + pago + ") deve ser igual ao total da nota (R$ " + vNF + ")"));
        }
        if (d.duplicatas() != null && !d.duplicatas().isEmpty()) {
            BigDecimal soma = BigDecimal.ZERO; int k = 0; java.time.LocalDate hoje = java.time.LocalDate.now();
            for (DadosNfe.Parcela x : d.duplicatas()) {
                k++;
                if (x.venc() == null) p.add(new Problema("Duplicata " + k, "vencimento obrigatório"));
                else if (x.venc().isBefore(hoje)) p.add(new Problema("Duplicata " + k, "vencimento anterior à data de emissão"));
                if (x.valor() == null || x.valor().signum() <= 0) p.add(new Problema("Duplicata " + k, "valor deve ser maior que zero")); else soma = soma.add(x.valor());
            }
            if (soma.compareTo(vNF) != 0) p.add(new Problema("Duplicatas", "a soma das duplicatas (R$ " + soma + ") deve ser igual ao total da nota (R$ " + vNF + ")"));
        }
        return p;
    }

    public static String resumo(List<Problema> l) {
        StringBuilder sb = new StringBuilder("Dados fiscais incompletos ou inválidos (" + l.size() + "): ");
        for (int i = 0; i < l.size(); i++) sb.append(i == 0 ? "" : " · ").append(l.get(i));
        return sb.toString();
    }
}
