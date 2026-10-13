package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.fiscal.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Lê os parâmetros fiscais (.env → FISCAL_*) e mantém o emissor (certificado A1 carregado uma vez). */
@ApplicationScoped
public class FiscalConfig {
    @ConfigProperty(name = "fruteira.fiscal.provider", defaultValue = "simulador") String provider;
    @ConfigProperty(name = "fruteira.fiscal.ambiente", defaultValue = "2") int ambiente;
    @ConfigProperty(name = "fruteira.fiscal.serie", defaultValue = "1") int serie;
    @ConfigProperty(name = "fruteira.fiscal.numero-inicial", defaultValue = "1") int numeroInicial;
    @ConfigProperty(name = "fruteira.fiscal.cnpj", defaultValue = "") String cnpj;
    @ConfigProperty(name = "fruteira.fiscal.razao", defaultValue = "") String razao;
    @ConfigProperty(name = "fruteira.fiscal.fantasia", defaultValue = "") String fantasia;
    @ConfigProperty(name = "fruteira.fiscal.ie", defaultValue = "") String ie;
    @ConfigProperty(name = "fruteira.fiscal.crt", defaultValue = "1") String crt;
    @ConfigProperty(name = "fruteira.fiscal.logradouro", defaultValue = "") String logradouro;
    @ConfigProperty(name = "fruteira.fiscal.numero", defaultValue = "S/N") String numero;
    @ConfigProperty(name = "fruteira.fiscal.bairro", defaultValue = "") String bairro;
    @ConfigProperty(name = "fruteira.fiscal.cmun", defaultValue = "") String cMun;
    @ConfigProperty(name = "fruteira.fiscal.municipio", defaultValue = "") String municipio;
    @ConfigProperty(name = "fruteira.fiscal.cep", defaultValue = "") String cep;
    @ConfigProperty(name = "fruteira.fiscal.cfop", defaultValue = "5102") String cfop;
    @ConfigProperty(name = "fruteira.fiscal.csosn", defaultValue = "102") String csosn;
    @ConfigProperty(name = "fruteira.fiscal.csc-id", defaultValue = "") String cscId;
    @ConfigProperty(name = "fruteira.fiscal.csc", defaultValue = "") String csc;
    @ConfigProperty(name = "fruteira.fiscal.cert-path", defaultValue = "") String certPath;
    @ConfigProperty(name = "fruteira.fiscal.cert-senha", defaultValue = "") String certSenha;
    @ConfigProperty(name = "fruteira.fiscal.trust-path", defaultValue = "") String trustPath;
    @ConfigProperty(name = "fruteira.fiscal.trust-senha", defaultValue = "") String trustSenha;
    @ConfigProperty(name = "fruteira.fiscal.url-autorizacao", defaultValue = "") String urlAutorizacao;
    @ConfigProperty(name = "fruteira.fiscal.url-consulta", defaultValue = "") String urlConsulta;
    @ConfigProperty(name = "fruteira.fiscal.url-evento", defaultValue = "") String urlEvento;
    @ConfigProperty(name = "fruteira.fiscal.url-qr", defaultValue = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx") String urlQr;
    @ConfigProperty(name = "fruteira.fiscal.url-chave", defaultValue = "www.sefaz.rs.gov.br/nfce/consulta") String urlChave;
    @ConfigProperty(name = "fruteira.fiscal.timeout-seg", defaultValue = "20") int timeoutSeg;

    private NfceEmissor emissor;

    public boolean real() { return "sefaz".equalsIgnoreCase(provider); }
    public String provider() { return provider; }
    public int ambiente() { return ambiente; }
    public int numeroInicial() { return numeroInicial; }

    Config config() {
        Config c = new Config();
        c.tpAmb = ambiente; c.serie = serie; c.cnpj = cnpj; c.razao = razao; c.fantasia = fantasia; c.ie = ie; c.crt = crt;
        c.logradouro = logradouro; c.numero = numero; c.bairro = bairro; c.cMun = cMun; c.municipio = municipio; c.cep = cep; c.cfop = cfop; c.csosn = csosn;
        c.cscId = cscId; c.csc = csc; c.certPath = certPath; c.certSenha = certSenha; c.trustPath = trustPath; c.trustSenha = trustSenha;
        c.urlAutorizacao = urlAutorizacao; c.urlConsulta = urlConsulta; c.urlEvento = urlEvento; c.urlQr = urlQr; c.urlChave = urlChave; c.timeoutSeg = timeoutSeg;
        return c;
    }

    /** Falta alguma configuração obrigatória? (devolve a lista, vazia se estiver tudo certo) */
    public java.util.List<String> pendencias() {
        var l = new java.util.ArrayList<String>();
        if (cnpj.replaceAll("\\D", "").length() != 14) l.add("FISCAL_CNPJ");
        if (razao.isBlank()) l.add("FISCAL_RAZAO");
        if (ie.isBlank()) l.add("FISCAL_IE");
        if (cMun.isBlank()) l.add("FISCAL_CMUN (código IBGE do município)");
        if (cscId.isBlank() || csc.isBlank()) l.add("FISCAL_CSC_ID / FISCAL_CSC (gerados no portal da SEFAZ-RS)");
        if (certPath.isBlank()) l.add("FISCAL_CERT_PATH (certificado A1 .pfx)");
        else if (!new java.io.File(certPath).canRead()) l.add("FISCAL_CERT_PATH: arquivo não encontrado");
        return l;
    }

    public synchronized NfceEmissor emissor() throws Exception {
        if (emissor == null) {
            var falta = pendencias();
            if (!falta.isEmpty()) throw new IllegalStateException("Configuração fiscal incompleta: " + String.join(", ", falta));
            Config c = config();
            emissor = new NfceEmissor(c, new SefazClient(c), Assinador.carregar(certPath, certSenha));
        }
        return emissor;
    }
}
