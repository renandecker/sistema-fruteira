package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.fiscal.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Lê os parâmetros fiscais (.env → FISCAL_*) e mantém o emissor (certificado A1 carregado uma vez). */
@ApplicationScoped
public class FiscalConfig {
    @ConfigProperty(name = "fruteira.fiscal.provider", defaultValue = "simulador") String provider;
    @ConfigProperty(name = "fruteira.fiscal.ambiente", defaultValue = "2") int ambiente;
    @ConfigProperty(name = "fruteira.fiscal.serie", defaultValue = "1") int serie;
    @ConfigProperty(name = "fruteira.fiscal.numero-inicial", defaultValue = "1") int numeroInicial;
    @ConfigProperty(name = "fruteira.fiscal.cnpj") Optional<String> cnpj;
    @ConfigProperty(name = "fruteira.fiscal.razao") Optional<String> razao;
    @ConfigProperty(name = "fruteira.fiscal.fantasia") Optional<String> fantasia;
    @ConfigProperty(name = "fruteira.fiscal.ie") Optional<String> ie;
    @ConfigProperty(name = "fruteira.fiscal.crt", defaultValue = "1") String crt;
    @ConfigProperty(name = "fruteira.fiscal.logradouro") Optional<String> logradouro;
    @ConfigProperty(name = "fruteira.fiscal.numero", defaultValue = "S/N") String numero;
    @ConfigProperty(name = "fruteira.fiscal.bairro") Optional<String> bairro;
    @ConfigProperty(name = "fruteira.fiscal.cmun") Optional<String> cMun;
    @ConfigProperty(name = "fruteira.fiscal.municipio") Optional<String> municipio;
    @ConfigProperty(name = "fruteira.fiscal.cep") Optional<String> cep;
    @ConfigProperty(name = "fruteira.fiscal.cfop", defaultValue = "5102") String cfop;
    @ConfigProperty(name = "fruteira.fiscal.csosn", defaultValue = "102") String csosn;
    @ConfigProperty(name = "fruteira.fiscal.csc-id") Optional<String> cscId;
    @ConfigProperty(name = "fruteira.fiscal.csc") Optional<String> csc;
    @ConfigProperty(name = "fruteira.fiscal.cert-path") Optional<String> certPath;
    @ConfigProperty(name = "fruteira.fiscal.cert-senha") Optional<String> certSenha;
    @ConfigProperty(name = "fruteira.fiscal.trust-path") Optional<String> trustPath;
    @ConfigProperty(name = "fruteira.fiscal.trust-senha") Optional<String> trustSenha;
    @ConfigProperty(name = "fruteira.fiscal.url-autorizacao") Optional<String> urlAutorizacao;
    @ConfigProperty(name = "fruteira.fiscal.url-consulta") Optional<String> urlConsulta;
    @ConfigProperty(name = "fruteira.fiscal.url-evento") Optional<String> urlEvento;
    @ConfigProperty(name = "fruteira.fiscal.url-qr", defaultValue = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx") String urlQr;
    @ConfigProperty(name = "fruteira.fiscal.url-chave", defaultValue = "www.sefaz.rs.gov.br/nfce/consulta") String urlChave;
    @ConfigProperty(name = "fruteira.fiscal.timeout-seg", defaultValue = "20") int timeoutSeg;

    private NfceEmissor emissor;

    /** Propriedade vazia = não configurada (Optional.empty): devolve "". */
    private static String v(Optional<String> o) { return o.orElse("").trim(); }

    public boolean real() { return "sefaz".equalsIgnoreCase(provider); }
    public String provider() { return provider; }
    public int ambiente() { return ambiente; }
    public int numeroInicial() { return numeroInicial; }

    Config config() {
        Config c = new Config();
        c.tpAmb = ambiente; c.serie = serie; c.cnpj = v(cnpj); c.razao = v(razao); c.fantasia = v(fantasia); c.ie = v(ie); c.crt = crt;
        c.logradouro = v(logradouro); c.numero = numero; c.bairro = v(bairro); c.cMun = v(cMun); c.municipio = v(municipio); c.cep = v(cep); c.cfop = cfop; c.csosn = csosn;
        c.cscId = v(cscId); c.csc = v(csc); c.certPath = v(certPath); c.certSenha = certSenha.orElse(""); c.trustPath = v(trustPath); c.trustSenha = trustSenha.orElse("");
        c.urlAutorizacao = v(urlAutorizacao); c.urlConsulta = v(urlConsulta); c.urlEvento = v(urlEvento); c.urlQr = urlQr; c.urlChave = urlChave; c.timeoutSeg = timeoutSeg;
        return c;
    }

    /** Falta alguma configuração obrigatória? (devolve a lista, vazia se estiver tudo certo) */
    public java.util.List<String> pendencias() {
        var l = new java.util.ArrayList<String>();
        if (v(cnpj).replaceAll("\\D", "").length() != 14) l.add("FISCAL_CNPJ");
        if (v(razao).isBlank()) l.add("FISCAL_RAZAO");
        if (v(ie).isBlank()) l.add("FISCAL_IE");
        if (v(cMun).isBlank()) l.add("FISCAL_CMUN (código IBGE do município)");
        if (v(cscId).isBlank() || v(csc).isBlank()) l.add("FISCAL_CSC_ID / FISCAL_CSC (gerados no portal da SEFAZ-RS)");
        if (v(certPath).isBlank()) l.add("FISCAL_CERT_PATH (certificado A1 .pfx)");
        else if (!new java.io.File(v(certPath)).canRead()) l.add("FISCAL_CERT_PATH: arquivo não encontrado");
        return l;
    }

    public synchronized NfceEmissor emissor() throws Exception {
        if (emissor == null) {
            var falta = pendencias();
            if (!falta.isEmpty()) throw new IllegalStateException("Configuração fiscal incompleta: " + String.join(", ", falta));
            Config c = config();
            emissor = new NfceEmissor(c, new SefazClient(c), Assinador.carregar(v(certPath), certSenha.orElse("")));
        }
        return emissor;
    }
}
