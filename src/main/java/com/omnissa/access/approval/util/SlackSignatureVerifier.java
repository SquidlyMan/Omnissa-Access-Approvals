package com.omnissa.access.approval.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies Slack request signatures (#50). Slack signs each request with an
 * HMAC-SHA256 of {@code v0:{timestamp}:{rawBody}} using the app's signing
 * secret and sends it as {@code X-Slack-Signature} alongside
 * {@code X-Slack-Request-Timestamp}. We authenticate the caller cryptographically
 * — never by session or "who can see the message" (see design §2.2).
 */
public final class SlackSignatureVerifier {

    /** Reject requests whose timestamp is older/newer than this (replay guard). */
    public static final long MAX_SKEW_SECONDS = 60 * 5;

    private SlackSignatureVerifier() {}

    /**
     * @param signingSecret Slack app signing secret (blank/null → always false)
     * @param timestampHeader value of X-Slack-Request-Timestamp
     * @param signatureHeader value of X-Slack-Signature (e.g. "v0=abc123…")
     * @param rawBody         the exact raw request body
     * @param nowEpochSeconds current time (injectable for tests)
     */
    public static boolean isValid(String signingSecret, String timestampHeader, String signatureHeader,
                                  String rawBody, long nowEpochSeconds) {
        if (isBlank(signingSecret) || isBlank(timestampHeader) || isBlank(signatureHeader) || rawBody == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - ts) > MAX_SKEW_SECONDS) {
            return false; // replay / stale
        }
        String expected = "v0=" + hmacSha256Hex(signingSecret, "v0:" + ts + ":" + rawBody);
        return constantTimeEquals(expected, signatureHeader.trim());
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
