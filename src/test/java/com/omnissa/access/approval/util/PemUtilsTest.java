package com.omnissa.access.approval.util;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemUtilsTest {

    /** Self-signed RSA cert (openssl-generated fixture). */
    private static final String CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDDzCCAfegAwIBAgIUbkUdwqPMlnqEy9ebc1Qef5FLflIwDQYJKoZIhvcNAQEL
            BQAwFzEVMBMGA1UEAwwMdGVzdC5leGFtcGxlMB4XDTI2MDcyMjAzMDA0OFoXDTM2
            MDcxOTAzMDA0OFowFzEVMBMGA1UEAwwMdGVzdC5leGFtcGxlMIIBIjANBgkqhkiG
            9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjIv4ZPTd7y8oTPx3HZyY8OugQVg858WlIxGK
            rTZ8fx/i/pAjn0+lei+VeemUeRsHyNCPoKvBgQYCUJ/tAY/qlnH0loKRPjTV9eIx
            bGJOT8JspFC7x/vltfP+aYpIOEaf9hS1K9K4TmXaNvW6Tl93F7D20V+PllVhbfLo
            yV0dHPzb0effJ9i4kjSPCxbKBxZSANvCBF+wl2SeUbTtDvY4CAWRdJv8RL6tn1Ow
            FTkVs91N00CFbnkbb1+SGxwyiFa7rdFiIdyt2jidXzMC4gkvMwDzkYM28AZVC1w4
            isyNg+88sAScfHqXaxBDAaMXwzv6rXKxOr+OjkqRUnazURSO6wIDAQABo1MwUTAd
            BgNVHQ4EFgQUcDuGqCTztIcK+hNVpgddBruCgf8wHwYDVR0jBBgwFoAUcDuGqCTz
            tIcK+hNVpgddBruCgf8wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOC
            AQEAbtUgYHtj7kv0nmcS0hSi+Tf5yL2H11eEb6y57DjjVUciWqemiMqNbFvOZWO1
            KN4YutN0WjGJn0qpnYLFBwyHkiqaO8bv7Cmjk7ciYv7UDsBujfLNNbLLc3luQl3N
            eY5fCpcPZf+l2+Jon+tQ6B1MrjbiDpNKMga8htYmOv7NfHDCcMe3k3riWG8PN5WJ
            xgsAqkvV0MBRLWNLQTUIo5tl3cwEU/3pVopyVap74lfaq0y+A9TWQMLRHO5N5rwD
            FZkyTrQLszQ43C63jYkORZdfBIMrHxblFNdmrfhM19Vie+PpCgaAVvBV1ltnHGDS
            LB8YdBRxzT/ru88PDqWxJNA8xQ==
            -----END CERTIFICATE-----
            """;

    private static String toPkcs8Pem(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void parsesGeneratedRsaPkcs8Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        PrivateKey parsed = PemUtils.parsePrivateKey(toPkcs8Pem(pair.getPrivate()));

        assertThat(parsed).isNotNull();
        assertThat(parsed.getAlgorithm()).isEqualTo("RSA");
        assertThat(parsed.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    void parsesGeneratedEcPkcs8Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        KeyPair pair = gen.generateKeyPair();

        PrivateKey parsed = PemUtils.parsePrivateKey(toPkcs8Pem(pair.getPrivate()));

        assertThat(parsed).isNotNull();
        assertThat(parsed.getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void rejectsPkcs1RsaKeyWithConversionHint() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJB\n-----END RSA PRIVATE KEY-----";
        assertThatThrownBy(() -> PemUtils.parsePrivateKey(pkcs1))
                .isInstanceOf(InvalidKeySpecException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void rejectsSec1EcKey() {
        String sec1 = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----";
        assertThatThrownBy(() -> PemUtils.parsePrivateKey(sec1))
                .isInstanceOf(InvalidKeySpecException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void rejectsEncryptedKey() {
        String enc = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIB\n-----END ENCRYPTED PRIVATE KEY-----";
        assertThatThrownBy(() -> PemUtils.parsePrivateKey(enc))
                .isInstanceOf(InvalidKeySpecException.class)
                .hasMessageContaining("passphrase");
    }

    @Test
    void rejectsInvalidBase64Key() {
        String bad = "-----BEGIN PRIVATE KEY-----\n@@@not-base64@@@\n-----END PRIVATE KEY-----";
        assertThatThrownBy(() -> PemUtils.parsePrivateKey(bad))
                .isInstanceOf(InvalidKeySpecException.class);
    }

    @Test
    void parsesSelfSignedCertificate() throws Exception {
        List<X509Certificate> certs = PemUtils.parseCertificates(CERT_PEM);
        assertThat(certs).hasSize(1);
        assertThat(certs.get(0).getSubjectX500Principal().getName()).contains("test.example");
    }

    @Test
    void emptyPemHasNoCertificates() {
        assertThatThrownBy(() -> PemUtils.parseCertificates("no certs here"))
                .isInstanceOf(java.security.cert.CertificateException.class);
    }
}
