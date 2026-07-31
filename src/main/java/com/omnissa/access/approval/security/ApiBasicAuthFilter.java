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

    public ApiBasicAuthFilter(String username, String password) {
        this.username = username;
        this.password = password;
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
