package br.com.fruteira.retaguarda.fiscal;

/** Parâmetros fiscais da loja (vêm do .env / application.properties). Só JDK. */
public class Config {
    public int tpAmb = 2;                 // 1 = produção · 2 = homologação
    public int cUF = 43;                  // 43 = RS
    public String uf = "RS";
    public int serie = 1;
    public String cnpj = "", razao = "", fantasia = "", ie = "";
    public String crt = "1";              // 1 = Simples Nacional (único regime montado neste emissor)
    public String logradouro = "", numero = "S/N", bairro = "", cMun = "", municipio = "", cep = "";
    public String cfop = "5102", csosn = "102";
    public String cscId = "", csc = "";   // Código de Segurança do Contribuinte (QR Code), gerado no portal da SEFAZ
    public String certPath = "", certSenha = "";          // certificado A1 (.pfx/.p12)
    public String trustPath = "", trustSenha = "";        // cadeia ICP-Brasil/SEFAZ (JKS/PKCS12), se o cacerts do JDK não a tiver
    public String urlAutorizacao = "", urlConsulta = "", urlEvento = "";
    public String urlQr = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx";
    public String urlChave = "www.sefaz.rs.gov.br/nfce/consulta";
    public int timeoutSeg = 20;
    public String verProc = "Fruteira 1.0";
    /** Justificativa padrão do modo contingência (mín. 15 caracteres). */
    public String justContingencia = "Contingencia offline: sem comunicacao com a SEFAZ";

    public boolean homologacao() { return tpAmb != 1; }

    /** Endereços padrão do RS (conferir no portal da SEFAZ-RS) quando não informados. */
    public void completarUrls() {
        String host = homologacao() ? "nfce-homologacao.sefazrs.rs.gov.br" : "nfce.sefazrs.rs.gov.br";
        if (urlAutorizacao.isBlank()) urlAutorizacao = "https://" + host + "/ws/NfeAutorizacao/NFeAutorizacao4.asmx";
        if (urlConsulta.isBlank()) urlConsulta = "https://" + host + "/ws/NfeConsulta/NfeConsulta4.asmx";
        if (urlEvento.isBlank()) urlEvento = "https://" + host + "/ws/recepcaoevento/recepcaoevento4.asmx";
    }
}
