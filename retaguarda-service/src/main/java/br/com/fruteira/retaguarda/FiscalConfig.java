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
    @ConfigProperty(name = "fruteira.fiscal.url-status") Optional<String> urlStatus;
    @ConfigProperty(name = "fruteira.fiscal.nfe-url-status") Optional<String> urlNfeStatus;
    @ConfigProperty(name = "fruteira.fiscal.url-qr", defaultValue = "https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx") String urlQr;
    @ConfigProperty(name = "fruteira.fiscal.url-chave", defaultValue = "www.sefaz.rs.gov.br/nfce/consulta") String urlChave;
    @ConfigProperty(name = "fruteira.fiscal.timeout-seg", defaultValue = "20") int timeoutSeg;
    @ConfigProperty(name = "fruteira.fiscal.im") Optional<String> im;
    @ConfigProperty(name = "fruteira.fiscal.nat-op", defaultValue = "VENDA DE MERCADORIA") String natOp;
    @ConfigProperty(name = "fruteira.fiscal.cst-icms", defaultValue = "40") String cstIcms;
    @ConfigProperty(name = "fruteira.fiscal.cst-pis", defaultValue = "07") String cstPis;
    @ConfigProperty(name = "fruteira.fiscal.cst-cofins", defaultValue = "07") String cstCofins;
    @ConfigProperty(name = "fruteira.fiscal.origem", defaultValue = "0") String origem;
    @ConfigProperty(name = "fruteira.fiscal.aliq-icms", defaultValue = "0") java.math.BigDecimal aliqIcms;
    @ConfigProperty(name = "fruteira.fiscal.aliq-pis", defaultValue = "0") java.math.BigDecimal aliqPis;
    @ConfigProperty(name = "fruteira.fiscal.aliq-cofins", defaultValue = "0") java.math.BigDecimal aliqCofins;
    @ConfigProperty(name = "fruteira.fiscal.ibscbs", defaultValue = "auto") String ibsCbs;
    @ConfigProperty(name = "fruteira.fiscal.ibscbs-cst") Optional<String> ibsCbsCst;
    @ConfigProperty(name = "fruteira.fiscal.ibscbs-cclasstrib") Optional<String> ibsCbsClassTrib;
    @ConfigProperty(name = "fruteira.fiscal.pibs-uf", defaultValue = "0.10") java.math.BigDecimal pIbsUf;
    @ConfigProperty(name = "fruteira.fiscal.pibs-mun", defaultValue = "0") java.math.BigDecimal pIbsMun;
    @ConfigProperty(name = "fruteira.fiscal.pcbs", defaultValue = "0.90") java.math.BigDecimal pCbs;
    /** Cartão só entra na nota com os dados do TEF (CNPJ da credenciadora, bandeira, autorização). Padrão: exige quando a emissão é real. */
    @ConfigProperty(name = "fruteira.fiscal.exige-tef") Optional<Boolean> exigeTef;

    @ConfigProperty(name = "fruteira.fiscal.nfe-serie", defaultValue = "1") int serieNfe;
    @ConfigProperty(name = "fruteira.fiscal.nfe-numero-inicial", defaultValue = "1") int numeroInicialNfe;
    @ConfigProperty(name = "fruteira.fiscal.nfe-url-autorizacao") Optional<String> urlNfeAutorizacao;
    @ConfigProperty(name = "fruteira.fiscal.nfe-url-consulta") Optional<String> urlNfeConsulta;
    @ConfigProperty(name = "fruteira.fiscal.nfe-url-evento") Optional<String> urlNfeEvento;

    private NfceEmissor emissor; private NfeEmissor emissorNfe;

    /** Propriedade vazia = não configurada (Optional.empty): devolve "". */
    private static String v(Optional<String> o) { return o.orElse("").trim(); }

    public boolean real() { return "sefaz".equalsIgnoreCase(provider); }
    public String provider() { return provider; }
    public int ambiente() { return ambiente; }
    public int numeroInicial() { return numeroInicial; }
    public int numeroInicialNfe() { return numeroInicialNfe; }

    Config config() {
        Config c = new Config();
        c.tpAmb = ambiente; c.serie = serie; c.cnpj = v(cnpj); c.razao = v(razao); c.fantasia = v(fantasia); c.ie = v(ie); c.crt = crt;
        c.logradouro = v(logradouro); c.numero = numero; c.bairro = v(bairro); c.cMun = v(cMun); c.municipio = v(municipio); c.cep = v(cep); c.cfop = cfop; c.csosn = csosn;
        c.cscId = v(cscId); c.csc = v(csc); c.certPath = v(certPath); c.certSenha = certSenha.orElse(""); c.trustPath = v(trustPath); c.trustSenha = trustSenha.orElse("");
        c.im = v(im); c.natOp = natOp; c.cstIcms = cstIcms; c.cstPis = cstPis; c.cstCofins = cstCofins; c.origem = origem; c.aliqIcms = aliqIcms; c.aliqPis = aliqPis; c.aliqCofins = aliqCofins;
        c.ibsCbs = ibsCbs; c.cstIbsCbs = v(ibsCbsCst); c.cClassTrib = v(ibsCbsClassTrib); c.pIbsUf = pIbsUf; c.pIbsMun = pIbsMun; c.pCbs = pCbs; c.exigeTef = exigeTef.orElse(real());
        c.serieNfe = serieNfe; c.urlNfeAutorizacao = v(urlNfeAutorizacao); c.urlNfeConsulta = v(urlNfeConsulta); c.urlNfeEvento = v(urlNfeEvento); c.urlStatus = v(urlStatus); c.urlNfeStatus = v(urlNfeStatus);
        c.urlAutorizacao = v(urlAutorizacao); c.urlConsulta = v(urlConsulta); c.urlEvento = v(urlEvento); c.urlQr = urlQr; c.urlChave = urlChave; c.timeoutSeg = timeoutSeg;
        return c;
    }

    /** Cadastro do emitente (CNPJ, IE, endereço completo, regime, CFOP/CST padrão, IBS/CBS): cada problema com o que corrigir. */
    public java.util.List<Validador.Problema> problemasEmitente() { return Validador.emitente(config()); }
    public Config configAtual() { return config(); }
    public boolean ibsCbsLigado() { return config().usaIbsCbs(); }

    /** Falta alguma configuração obrigatória? (devolve a lista, vazia se estiver tudo certo) */
    public java.util.List<String> pendencias() {
        var l = new java.util.ArrayList<String>();
        for (Validador.Problema p : problemasEmitente()) l.add(p.toString());
        if (v(cscId).isBlank() || v(csc).isBlank()) l.add("FISCAL_CSC_ID / FISCAL_CSC (gerados no portal da SEFAZ-RS)");
        if (Certificado.resolver(v(certPath)) == null) l.add(v(certPath).isBlank() ? "certificado A1: coloque o certificado.pfx na raiz do projeto (ou FISCAL_CERT_PATH)" : "FISCAL_CERT_PATH: arquivo não encontrado");
        else if (certSenha.orElse("").isEmpty()) l.add("FISCAL_CERT_SENHA (senha do certificado A1)");
        else { var inf = certificado(); if (inf != null) l.addAll(inf.problemas()); }
        return l;
    }

    /** Certificado A1 lido e conferido (titular, CNPJ, validade); null se não achou o arquivo ou a senha não abre. O erro de leitura fica em {@link #erroCertificado()}. */
    private volatile Certificado.Info infoCert; private volatile String erroCert; private volatile long infoEm;
    public synchronized Certificado.Info certificado() {
        if (infoCert != null && System.currentTimeMillis() - infoEm < 60_000) return infoCert;       // relê no máximo 1x por minuto (troca do arquivo vale sem reiniciar)
        try {
            Assinador.Chave k = Assinador.carregar(v(certPath), certSenha.orElse(""));
            infoCert = Certificado.inspecionar(k, v(cnpj), k.arquivo(), java.time.Instant.now()); erroCert = null;
        } catch (Exception e) { infoCert = null; erroCert = e.getMessage(); }
        infoEm = System.currentTimeMillis();
        return infoCert;
    }
    public String erroCertificado() { certificado(); return erroCert; }

    /** Situação do certificado para as telas (sem expor a senha nem o caminho completo). */
    public java.util.Map<String, Object> certificadoResumo() {
        var m = new java.util.LinkedHashMap<String, Object>();
        var i = certificado();
        if (i == null) { m.put("ok", false); m.put("erro", erroCert != null ? erroCert : "Certificado A1 não encontrado"); return m; }
        m.put("ok", i.ok()); m.put("titular", i.titular()); m.put("cnpj", i.cnpj()); m.put("validoAte", i.validoAte()); m.put("diasRestantes", i.diasRestantes());
        m.put("arquivo", new java.io.File(i.arquivo()).getName()); m.put("problemas", i.problemas()); m.put("avisos", i.avisos());
        return m;
    }

    public synchronized NfceEmissor emissor() throws Exception {
        if (emissor != null && certificado() != null && !certificado().ok()) { emissor = null; emissorNfe = null; }      // venceu/trocou: não emite com certificado inválido
        if (emissor == null) {
            var falta = pendencias();
            if (!falta.isEmpty()) throw new IllegalStateException("Configuração fiscal incompleta: " + String.join(", ", falta));
            Config c = config();
            emissor = new NfceEmissor(c, new SefazClient(c), Assinador.carregar(v(certPath), certSenha.orElse("")));
        }
        return emissor;
    }

    /** Emissor de NF-e (modelo 55): mesmo certificado e CSC não necessário (NF-e não usa QR Code). */
    public synchronized NfeEmissor emissorNfe() throws Exception {
        if (emissorNfe != null && certificado() != null && !certificado().ok()) { emissor = null; emissorNfe = null; }
        if (emissorNfe == null) {
            var falta = pendencias().stream().filter(x -> !x.startsWith("FISCAL_CSC")).toList();      // NF-e dispensa o CSC
            if (!falta.isEmpty()) throw new IllegalStateException("Configuração fiscal incompleta: " + String.join(", ", falta));
            Config c = config();
            emissorNfe = new NfeEmissor(c, new SefazClient(c), Assinador.carregar(v(certPath), certSenha.orElse("")));
        }
        return emissorNfe;
    }
}
