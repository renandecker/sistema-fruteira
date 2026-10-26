package br.com.fruteira.retaguarda.fiscal;

/** Parâmetros fiscais da loja (vêm do .env / application.properties). Só JDK. */
public class Config {
    public int tpAmb = 2;                 // 1 = produção · 2 = homologação
    public int cUF = 43;                  // 43 = RS
    public String uf = "RS";
    public int serie = 1;
    public String cnpj = "", razao = "", fantasia = "", ie = "";
    public String crt = "1";              // 1 = Simples Nacional (CSOSN) · 3 = Regime Normal / Lucro Presumido (CST)
    public String logradouro = "", numero = "S/N", bairro = "", cMun = "", municipio = "", cep = "";
    public String cfop = "5102", csosn = "102";
    public String im = "";                // Inscrição Municipal (opcional na NFC-e)
    /** Regime normal (CRT 3): CST do ICMS, e PIS/COFINS. CRT 1 usa o CSOSN acima. Padrão de hortifrúti in natura: ICMS isento (40), PIS/COFINS alíquota zero (06). */
    public String cstIcms = "40", cstPis = "07", cstCofins = "07", origem = "0";
    public java.math.BigDecimal aliqIcms = java.math.BigDecimal.ZERO, aliqPis = java.math.BigDecimal.ZERO, aliqCofins = java.math.BigDecimal.ZERO;   // % (só quando o CST tributa: ICMS 00, PIS/COFINS 01)
    /** Cartão só vale na nota com os dados do TEF (CNPJ da credenciadora, bandeira, autorização). */
    public boolean exigeTef = false;
    public String natOp = "VENDA DE MERCADORIA";
    /** Reforma tributária (NT 2025.002): auto = informa IBS/CBS só no regime normal (obrigatório desde 03/08/2026); Simples/MEI só a partir de 04/01/2027 (facultativo antes); on/off forçam. */
    public String ibsCbs = "auto";
    /** Padrões de IBS/CBS (CST de 3 dígitos e cClassTrib de 6, da tabela oficial) e alíquotas de 2026 (teste): CBS 0,9% · IBS 0,1% (UF) e 0% (município). */
    public String cstIbsCbs = "", cClassTrib = "";
    public java.math.BigDecimal pIbsUf = new java.math.BigDecimal("0.10"), pIbsMun = java.math.BigDecimal.ZERO, pCbs = new java.math.BigDecimal("0.90");
    public String cscId = "", csc = "";   // Código de Segurança do Contribuinte (QR Code), gerado no portal da SEFAZ
    public String certPath = "", certSenha = "";          // certificado A1 (.pfx/.p12)
    public String trustPath = "", trustSenha = "";        // cadeia ICP-Brasil/SEFAZ (JKS/PKCS12), se o cacerts do JDK não a tiver
    public String urlAutorizacao = "", urlConsulta = "", urlEvento = "", urlStatus = "";
    public String urlQr = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    public String urlChave = "www.sefaz.rs.gov.br/nfce/consulta";
    public int timeoutSeg = 20;
    public String verProc = "Fruteira 1.0";
    /** Justificativa padrão do modo contingência (mín. 15 caracteres). */
    public String justContingencia = "Contingencia offline: sem comunicacao com a SEFAZ";

    // ---- NF-e (modelo 55): série e numeração próprias, webservices da NF-e (diferentes dos da NFC-e) ----
    public int serieNfe = 1;
    public String urlNfeAutorizacao = "", urlNfeConsulta = "", urlNfeEvento = "", urlNfeStatus = "";
    public boolean homologacao() { return tpAmb != 1; }
    public boolean simples() { return !"3".equals(crt); }
    /** O grupo IBS/CBS vai na nota? */
    public boolean usaIbsCbs() { return "on".equalsIgnoreCase(ibsCbs) || ("auto".equalsIgnoreCase(ibsCbs) && !simples()); }

    /** Endereços padrão do RS (conferir no portal da SEFAZ-RS) quando não informados. */
    public void completarUrls() {
        String host = homologacao() ? "nfce-homologacao.sefazrs.rs.gov.br" : "nfce.sefazrs.rs.gov.br";
        if (urlAutorizacao.isBlank()) urlAutorizacao = "https://" + host + "/ws/NfeAutorizacao/NFeAutorizacao4.asmx";
        if (urlConsulta.isBlank()) urlConsulta = "https://" + host + "/ws/NfeConsulta/NfeConsulta4.asmx";
        String h2 = homologacao() ? "nfe-homologacao.sefazrs.rs.gov.br" : "nfe.sefazrs.rs.gov.br";     // conferir no portal da SEFAZ-RS (FISCAL_NFE_URL_*)
        if (urlNfeAutorizacao.isBlank()) urlNfeAutorizacao = "https://" + h2 + "/ws/NfeAutorizacao/NFeAutorizacao4.asmx";
        if (urlNfeConsulta.isBlank()) urlNfeConsulta = "https://" + h2 + "/ws/NfeConsulta/NfeConsulta4.asmx";
        if (urlNfeEvento.isBlank()) urlNfeEvento = "https://" + h2 + "/ws/recepcaoevento/recepcaoevento4.asmx";
        if (urlStatus.isBlank()) urlStatus = "https://" + host + "/ws/NfeStatusServico/NfeStatusServico4.asmx";
        if (urlNfeStatus.isBlank()) urlNfeStatus = "https://" + h2 + "/ws/NfeStatusServico/NfeStatusServico4.asmx";
        if (urlEvento.isBlank()) urlEvento = "https://" + host + "/ws/recepcaoevento/recepcaoevento4.asmx";
    }
}
