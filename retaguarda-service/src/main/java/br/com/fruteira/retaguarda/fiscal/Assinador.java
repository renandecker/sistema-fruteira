package br.com.fruteira.retaguarda.fiscal;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.crypto.dsig.spec.*;
import org.w3c.dom.*;

/** Assinatura XMLDSig do padrão NF-e (enveloped, C14N, RSA-SHA1/SHA1) com certificado A1. */
public final class Assinador {
    private Assinador() {}

    public record Chave(PrivateKey privada, X509Certificate cert) {}

    public static Chave carregar(String caminho, String senha) throws Exception {
        if (caminho == null || caminho.isBlank()) throw new IllegalStateException("Certificado A1 não configurado (FISCAL_CERT_PATH)");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(caminho)) { ks.load(in, senha == null ? new char[0] : senha.toCharArray()); }
        for (String a : Collections.list(ks.aliases()))
            if (ks.isKeyEntry(a)) return new Chave((PrivateKey) ks.getKey(a, senha == null ? new char[0] : senha.toCharArray()), (X509Certificate) ks.getCertificate(a));
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
    }

    /** DigestValue (base64) da assinatura do documento. */
    public static String digestValue(Document doc) {
        return doc.getElementsByTagNameNS(XMLSignature.XMLNS, "DigestValue").item(0).getTextContent().trim();
    }
}
