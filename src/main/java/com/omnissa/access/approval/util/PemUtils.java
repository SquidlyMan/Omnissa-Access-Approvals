package com.omnissa.access.approval.util;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Minimal PEM parsing helpers for {@link TcpSyslogAppender} TLS material.
 * Package-private on purpose — this is not a general-purpose crypto utility.
 */
final class PemUtils {

    private PemUtils() {
    }

    /**
     * Parses one or more concatenated X.509 certificates from PEM text
     * (CertificateFactory handles multi-cert PEM bundles natively).
     */
    static List<X509Certificate> parseCertificates(String pem) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : factory.generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))) {
            certificates.add((X509Certificate) certificate);
        }
        if (certificates.isEmpty()) {
            throw new CertificateException("No X.509 certificates found in PEM input");
        }
        return certificates;
    }

    /**
     * Parses an unencrypted PKCS#8 ("BEGIN PRIVATE KEY") private key, trying
     * the EC KeyFactory first and falling back to RSA. Legacy PKCS#1/SEC1
     * ("BEGIN RSA/EC PRIVATE KEY") input is rejected with a conversion hint.
     */
    static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")) {
            throw new InvalidKeySpecException(
                    "Private key is in legacy PKCS#1/SEC1 format; convert it to PKCS#8 with "
                            + "`openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem`");
        }
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw new InvalidKeySpecException(
                    "Private key is passphrase-protected; provide an unencrypted PKCS#8 key "
                            + "(`openssl pkcs8 -topk8 -nocrypt`)");
        }
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException(
                    "Private key PEM is not valid base64 — expected an unencrypted PKCS#8 "
                            + "\"BEGIN PRIVATE KEY\" block: " + e.getMessage());
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        } catch (InvalidKeySpecException e) {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        }
    }
}
