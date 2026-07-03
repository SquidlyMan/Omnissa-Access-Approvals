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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiting per client IP for the inbound Omnissa Access
 * callout endpoint (POST /api/approvals/new). A no-op when the configured
 * limit is 0. OPTIONS requests always pass — Omnissa Access probes with
 * OPTIONS when the approvals settings are saved.
 *
 * Registered via FilterRegistrationBean at HIGHEST_PRECEDENCE so cheap
 * rejection happens before the basic-auth filter and Spring Security chain.
 */
public class RateLimitFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_IPS = 10_000;

    private final int limitPerMinute;

    /** IP → [windowStartMillis, count]; mutated under compute()'s per-key lock. */
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (limitPerMinute <= 0 || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        evictStaleEntries(now);

        String ip = clientIp(req);
        long[] window = windows.compute(ip, (key, value) -> {
            if (value == null || now - value[0] >= WINDOW_MILLIS) {
                return new long[]{now, 1};
            }
            value[1]++;
            return value;
        });

        if (window[1] > limitPerMinute) {
            logger.warn("Rate limit exceeded for {} on {} ({} requests in current window, limit {}/min)",
                    ip, req.getRequestURI(), window[1], limitPerMinute);
            res.setStatus(429);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * First value of X-Forwarded-For when present (the app sits behind a
     * reverse proxy that sets it), else the socket address.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Opportunistic eviction so the map can't grow without bound. */
    private void evictStaleEntries(long now) {
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue()[0] >= WINDOW_MILLIS);
        }
    }
}
