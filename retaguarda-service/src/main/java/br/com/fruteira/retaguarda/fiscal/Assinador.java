package br.com.fruteira.retaguarda.fiscal;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.crypto.dsig.spec.*;
import org.w3c.dom.*;

/** Assinatura XMLDSig do padrão NF-e (enveloped, C14N, RSA-SHA1/SHA1) com certificado A1. */
public final class Assinador {
    private Assinador() {}

    public record Chave(PrivateKey privada, X509Certificate cert, String arquivo) {
        public Chave(PrivateKey privada, X509Certificate cert) { this(privada, cert, ""); }
    }

    /** Carrega o A1 (.pfx/.p12). Caminho vazio = certificado.pfx na raiz do projeto (ver {@link Certificado#resolver}). */
    public static Chave carregar(String caminho, String senha) throws Exception {
        java.io.File arq = Certificado.resolver(caminho);
        if (arq == null) throw new IllegalStateException("Certificado A1 não encontrado: coloque o " + Certificado.PADRAO + " na raiz do projeto ou aponte FISCAL_CERT_PATH"
            + (caminho == null || caminho.isBlank() ? "" : " (procurado: " + caminho + ")"));
        char[] s = senha == null ? new char[0] : senha.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(arq)) { ks.load(in, s); }
        catch (java.io.IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("password") || m.contains("mac") || m.contains("keystore was tampered")) throw new IllegalStateException("Senha do certificado A1 incorreta (FISCAL_CERT_SENHA)", e);
            throw new IllegalStateException("Arquivo do certificado inválido: precisa ser um .pfx/.p12 (PKCS#12) — " + e.getMessage(), e);
        }
        for (String a : Collections.list(ks.aliases()))
            if (ks.isKeyEntry(a)) return new Chave((PrivateKey) ks.getKey(a, s), (X509Certificate) ks.getCertificate(a), arq.getPath());
        throw new IllegalStateException("O arquivo do certificado não tem chave privada");
    }

    /** Assina o elemento de id {@code id} e anexa {@code <Signature>} como último filho de {@code pai}. */
    public static void assinar(Document doc, Element pai, Element alvo, Chave chave) throws Exception {
        String id = alvo.getAttribute("Id");
        alvo.setIdAttribute("Id", true);
        XMLSignatureFactory f = XMLSignatureFactory.getInstance("DOM");
        Reference ref = f.newReference("#" + id, f.newDigestMethod(DigestMethod.SHA1, null),
            List.of(f.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                    f.newTransform("http://www.w3.org/TR/2001/REC-xml-c14n-20010315", (TransformParameterSpec) null)), null, null);
        SignedInfo si = f.newSignedInfo(f.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
            f.newSignatureMethod(SignatureMethod.RSA_SHA1, null), List.of(ref));
        KeyInfoFactory kif = f.getKeyInfoFactory();
        KeyInfo ki = kif.newKeyInfo(List.of(kif.newX509Data(List.of(chave.cert()))));
        DOMSignContext ctx = new DOMSignContext(chave.privada(), pai);
        ctx.setDefaultNamespacePrefix("");              // <Signature xmlns="..."> sem prefixo, como a SEFAZ espera
        f.newXMLSignature(si, ki).sign(ctx);
        if (!verificar(doc, alvo, chave)) throw new IllegalStateException("A assinatura gerada não confere com o certificado (não enviada à SEFAZ)");
    }

    /** Confere a assinatura do documento (digest + valor) com a chave pública do certificado: nunca envia à SEFAZ o que ela rejeitaria por assinatura (cStat 297/298). */
    public static boolean verificar(Document doc, Element alvo, Chave chave) throws Exception {
        alvo.setIdAttribute("Id", true);
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (sigs.getLength() != 1) return false;
        DOMValidateContext vc = new DOMValidateContext(chave.cert().getPublicKey(), sigs.item(0));
        vc.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);          // o padrão da NF-e ainda usa SHA-1
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(vc).validate(vc);
    }

    /** DigestValue (base64) da assinatura do documento. */
    public static String digestValue(Document doc) {
        return doc.getElementsByTagNameNS(XMLSignature.XMLNS, "DigestValue").item(0).getTextContent().trim();
    }
}
