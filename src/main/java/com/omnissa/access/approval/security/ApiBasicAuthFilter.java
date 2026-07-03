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

        logger.warn("Rejected unauthenticated callout request from {}", req.getRemoteAddr());
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Basic realm=\"approval-api\"");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
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
