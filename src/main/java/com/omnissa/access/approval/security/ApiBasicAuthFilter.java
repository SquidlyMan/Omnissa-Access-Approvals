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
    private final boolean challengeOptions;

    public ApiBasicAuthFilter(String username, String password) {
        this(username, password, true);
    }

    public ApiBasicAuthFilter(String username, String password, boolean challengeOptions) {
        this.username = username;
        this.password = password;
        this.challengeOptions = challengeOptions;
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
        // Omnissa Access decides whether this endpoint needs credentials by
        // probing it with OPTIONS, and only re-decides when its approvals
        // settings are saved. Challenging that probe is therefore REQUIRED: an
        // exempt probe answers "no authentication here", Access believes it, and
        // every subsequent callout arrives with no Authorization header while
        // the tenant holds a perfectly good username and password.
        //
        // Observed directly. With the probe exempt, callouts arrived bare for as
        // long as we watched. Challenging it and re-saving the settings in Access
        // produced an authenticated probe within seconds and an accepted callout
        // immediately after. The exemption was not a convenience — it was the
        // defect.
        //
        // Kept as a flag so it can be turned off if a tenant behaves differently,
        // but the default is now to challenge.
        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) && !challengeOptions) {
            logger.warn("Answering an OPTIONS probe from {} WITHOUT a challenge "
                    + "(omnissa.api.challenge-options=false). Omnissa Access uses this probe to "
                    + "decide whether credentials are required, so it will conclude they are not "
                    + "and send callouts unauthenticated.", req.getRemoteAddr());
            chain.doFilter(request, response);
            return;
        }

        if (isAuthorized(req.getHeader("Authorization"))) {
            // Recorded so an authenticated callout can be told apart from one
            // that is merely reaching the controller: Access posts two message
            // types to this path, and knowing which of them authenticates is the
            // difference between "solved" and "solved for one of two cases".
            logger.info("Authenticated {} on the callout endpoint from {} (content-type {})",
                    req.getMethod(), req.getRemoteAddr(), req.getContentType());
            chain.doFilter(request, response);
            return;
        }

        logger.warn("Rejected callout request from {}: {}", req.getRemoteAddr(),
                diagnose(req.getHeader("Authorization")));
        logger.warn("  what it actually sent: {}", inventory(req));
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Basic realm=\"approval-api\"");
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
