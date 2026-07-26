package com.omnissa.access.approval.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies {@link LoginThrottle} to the local login form.
 *
 * <p>Sits in front of authentication so the delay is paid before the password is
 * checked, and so a rejected address never reaches the authentication manager at
 * all.
 *
 * <p>The delay deliberately applies to <em>every</em> attempt once the counter is
 * up, including correct ones. Delaying only failures would leak which guesses
 * were right: an attacker could distinguish a valid password by its faster
 * response.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginThrottleFilter.class);

    private final LoginThrottle throttle;

    public LoginThrottleFilter(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = clientIp(request);
        String username = request.getParameter("username");

        if (throttle.shouldReject(ip)) {
            logger.warn("Rejecting local login from {} — too many recent failures", ip);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Too many failed sign-in attempts. "
                    + "Wait a few minutes and try again.\"}");
            return;
        }

        long delay = throttle.delayMillis(ip, username);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /** Only the login POST is throttled; the page itself must stay reachable. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/login/local".equals(request.getServletPath()));
    }

    /** First X-Forwarded-For value when behind a reverse proxy, else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
