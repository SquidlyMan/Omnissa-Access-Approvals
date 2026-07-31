package com.omnissa.access.approval.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Optional HTTP Basic auth on the inbound Omnissa Access callout endpoint
 * (POST /api/approvals/new). Only active when omnissa.api.username is set —
 * otherwise the endpoint stays open, matching prior behavior.
 *
 * Registered via FilterRegistrationBean at HIGHEST_PRECEDENCE so it runs
 * before the Spring Security chain (which permits the endpoint). OPTIONS
 * requests are always allowed through: Omnissa Access probes with an
 * unauthenticated OPTIONS when the approvals settings are saved.
 */
public class ApiBasicAuthFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(ApiBasicAuthFilter.class);

    private final String username;
    private final String password;
    private final boolean digestProbe;

    private static final java.security.SecureRandom NONCES = new java.security.SecureRandom();

    public ApiBasicAuthFilter(String username, String password) {
        this(username, password, false);
    }

    public ApiBasicAuthFilter(String username, String password, boolean digestProbe) {
        this.username = username;
        this.password = password;
        this.digestProbe = digestProbe;
    }

    /**
     * A Digest challenge offered alongside Basic, purely to see whether the
     * caller answers it. <strong>A Digest response is never accepted</strong> —
     * {@link #isAuthorized} matches only {@code Basic}, so this cannot become an
     * authentication path by accident.
     *
     * <p>Why offer a scheme we will not honour: Omnissa Access holds credentials
     * for this callout, receives a {@code Basic} challenge, and answers with
     * nothing at all — observed, not assumed. A client that performs only Digest
     * behaves exactly that way, because it finds no scheme it is willing to use.
     * Offering Digest costs nothing if that guess is wrong and identifies the
     * mechanism if it is right.
     *
     * <p>Off by default and gated on {@code OMNISSA_API_DIGEST_PROBE}. Advertising
     * two schemes permanently would let a client prefer the one that can never
     * succeed — a browser typically picks the stronger — so this stays an
     * experiment that is switched on deliberately and switched off again.
     * Verifying Digest properly means nonce tracking, replay windows and
     * {@code qop} handling; a half-built verifier would be worse than none.
     */
    private String digestChallenge() {
        byte[] raw = new byte[16];
        NONCES.nextBytes(raw);
        StringBuilder nonce = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            nonce.append(String.format("%02x", b));
        }
        return "Digest realm=\"approval-api\", qop=\"auth\", nonce=\"" + nonce
                + "\", algorithm=MD5";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // No credentials configured — endpoint stays open.
        if (username == null || username.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        // Omnissa Access probes with OPTIONS and no credentials when saving
        // its approvals settings — must never be challenged.
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (isAuthorized(req.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }

        logger.warn("Rejected callout request from {}: {}", req.getRemoteAddr(),
                diagnose(req.getHeader("Authorization")));
        logger.warn("  what it actually sent: {}", inventory(req));
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Basic first: a client that takes the first acceptable scheme keeps
        // working exactly as it does today.
        res.setHeader("WWW-Authenticate", "Basic realm=\"approval-api\"");
        if (digestProbe) {
            res.addHeader("WWW-Authenticate", digestChallenge());
        }
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    /**
     * Why a rejection happened, in terms of what the caller actually sent.
     *
     * <p>Every rejection used to read "Rejected unauthenticated callout request",
     * which cannot distinguish the two faults that matter: Omnissa Access sending
     * <em>no</em> credentials because the approvals settings were never saved with
     * them, versus sending the <em>wrong</em> ones. Those need opposite fixes, and
     * with one message the only way to tell them apart was to guess. A 401 nobody
     * can diagnose is its own defect.
     *
     * <p>Everything reported here is <strong>supplied by the caller</strong> — the
     * username it presented and the length of the secret it presented. The
     * configured password is never logged, and neither is the presented one: a
     * length is enough to recognise truncation, which is the failure a console
     * with a shorter field limit produces, without putting a credential in a log
     * that ships to syslog.
     */
    private String diagnose(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return "no Authorization header was sent. If this is Omnissa Access, its "
                    + "approvals settings have no credentials saved — set Username and "
                    + "Password there to match OMNISSA_API_USERNAME / OMNISSA_API_PASSWORD";
        }
        if (authHeader.regionMatches(true, 0, "Digest ", 0, 7)) {
            // The finding the probe exists for. Report the parameters that
            // identify the caller and the scheme it chose; the response hash is
            // derived from the password, so only its presence is noted.
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            for (String part : authHeader.substring(7).split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    params.put(part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT),
                            part.substring(eq + 1).trim().replaceAll("^\"|\"$", ""));
                }
            }
            String responseHash = params.remove("response");
            params.remove("cnonce");
            return "*** THE CALLER ANSWERED THE DIGEST CHALLENGE *** username='"
                    + params.get("username") + "', algorithm=" + params.get("algorithm")
                    + ", qop=" + params.get("qop")
                    + ", response present=" + (responseHash != null && !responseHash.isBlank())
                    + ". Digest is NOT accepted here — this is a probe. If this line names "
                    + "Omnissa Access, Digest is the mechanism and is worth implementing properly.";
        }
        if (!authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            int space = authHeader.indexOf(' ');
            return "Authorization used the '" + (space > 0 ? authHeader.substring(0, space) : "unknown")
                    + "' scheme; this endpoint expects Basic";
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "the Basic credentials were not valid base64";
        }
        int sep = decoded.indexOf(':');
        if (sep < 0) {
            return "the decoded Basic credentials contained no ':' separator";
        }
        String presentedUser = decoded.substring(0, sep);
        int presentedPasswordLength = decoded.length() - sep - 1;

        boolean userOk = MessageDigest.isEqual(presentedUser.getBytes(StandardCharsets.UTF_8),
                username.getBytes(StandardCharsets.UTF_8));
        String userPart = userOk
                ? "username '" + presentedUser + "' matched"
                : "username '" + presentedUser + "' did NOT match the configured one";
        String passPart = presentedPasswordLength == password.length()
                ? "password was the expected length (" + presentedPasswordLength
                        + ") but did not match — check for a transcription error"
                : "password was " + presentedPasswordLength + " characters, expected "
                        + password.length() + " — a shorter value usually means the field it "
                        + "was typed into truncated it";
        return userPart + "; " + passPart;
    }

    /** Header names that carry a credential in some product's convention. */
    private static final java.util.Set<String> CREDENTIAL_BEARING = java.util.Set.of(
            "authorization", "proxy-authorization", "x-api-key", "x-apikey",
            "x-auth-token", "x-access-token", "x-authentication", "x-auth",
            "api-key", "apikey", "token", "x-shared-secret", "x-signature",
            "x-hub-signature", "x-hub-signature-256", "x-vmware-authorization");

    /**
     * Everything the caller sent, described without quoting any of it.
     *
     * <p>The message above answers "were the Basic credentials right", and that
     * is only useful if the caller uses {@code Authorization} at all. Omnissa
     * Access stores a username and password for this callout and demonstrably
     * sends neither there — so either it uses another convention, or it sends
     * nothing. Reporting only the absence of one header makes those two look
     * identical, and states the second as though it were established.
     *
     * <p>So: every header name, the names of any query parameters, and the
     * length of anything credential-shaped. <strong>No value is logged</strong> —
     * names and lengths only. These lines go to syslog over UDP, and a header
     * dump is exactly the kind of convenience that quietly becomes a credential
     * leak.
     */
    private String inventory(HttpServletRequest req) {
        java.util.List<String> headers = new java.util.ArrayList<>();
        java.util.List<String> credentialish = new java.util.ArrayList<>();

        java.util.Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.add(name);
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (CREDENTIAL_BEARING.contains(lower)
                    || lower.contains("auth") || lower.contains("secret")
                    || lower.contains("credential") || lower.contains("passw")) {
                String value = req.getHeader(name);
                credentialish.add(name + "(" + (value == null ? 0 : value.length()) + " chars)");
            }
        }
        headers.sort(String.CASE_INSENSITIVE_ORDER);

        StringBuilder out = new StringBuilder();
        out.append("headers=").append(headers);
        if (!credentialish.isEmpty()) {
            out.append("; credential-shaped=").append(credentialish);
        }
        String query = req.getQueryString();
        if (query != null && !query.isBlank()) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                keys.add(eq < 0 ? pair : pair.substring(0, eq));
            }
            out.append("; query-keys=").append(keys);
        }
        out.append("; content-type=").append(req.getContentType());
        return out.toString();
    }

    private boolean isAuthorized(String authHeader) {
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int sep = decoded.indexOf(':');
        if (sep < 0) {
            return false;
        }
        // Constant-time comparison to avoid credential timing leaks.
        boolean userOk = MessageDigest.isEqual(
                decoded.substring(0, sep).getBytes(StandardCharsets.UTF_8),
                username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(
                decoded.substring(sep + 1).getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
        return userOk && passOk;
    }
}
