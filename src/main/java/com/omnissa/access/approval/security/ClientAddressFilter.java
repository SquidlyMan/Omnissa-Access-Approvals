package com.omnissa.access.approval.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Records the true socket peer and the raw {@code X-Forwarded-For} header before
 * anything else can rewrite them.
 *
 * <p>This exists because {@code server.forward-headers-strategy=framework} is
 * set, which puts Spring's {@code ForwardedHeaderFilter} in the chain. That
 * filter is doing its job — it makes redirect URLs and the OIDC redirect URI
 * correct behind a reverse proxy — but two of its effects are fatal to using a
 * client address as a security key:
 *
 * <ul>
 *   <li>It <strong>rewrites {@code getRemoteAddr()} to the first
 *       {@code X-Forwarded-For} entry</strong>, which is whatever the original
 *       caller sent. Proxies <em>append</em> to that header, so the leftmost
 *       value is client-supplied, never proxy-supplied.</li>
 *   <li>It <strong>strips the {@code X-Forwarded-*} headers</strong> once
 *       processed, so a later filter cannot re-read the chain.</li>
 * </ul>
 *
 * <p>After that filter runs there is no way left to learn the real peer, and
 * {@code getRemoteAddr()} looks authoritative while being entirely under the
 * caller's control. Every rate limit and login throttle keyed on it was
 * therefore bypassable by varying a header.
 *
 * <p><strong>Why a listener and not a filter.</strong> The first attempt was a
 * filter at {@code HIGHEST_PRECEDENCE}. Spring Boot registers
 * {@code ForwardedHeaderFilter} at that same precedence, and equal orders leave
 * the sequence unspecified — so the fix would have depended on a tie whose
 * outcome is not defined, and would have failed silently if it ever resolved the
 * other way. {@code requestInitialized} fires when the request enters the
 * container, before any filter runs at all, which removes the question rather
 * than answering it.
 */
public class ClientAddressFilter implements ServletRequestListener {

    /** True socket peer, before any forwarded-header rewriting. */
    public static final String PEER_ATTRIBUTE = "com.omnissa.client.peer";

    /** Raw X-Forwarded-For, before the forwarded-header filter strips it. */
    public static final String FORWARDED_ATTRIBUTE = "com.omnissa.client.forwarded";

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        ServletRequest request = event.getServletRequest();
        if (!(request instanceof HttpServletRequest http)) {
            return;
        }
        capture(http);
    }

    /** Also called directly by tests, which have no servlet container to fire the event. */
    public static void capture(HttpServletRequest http) {
        if (http.getAttribute(PEER_ATTRIBUTE) != null) {
            return;
        }
        http.setAttribute(PEER_ATTRIBUTE, http.getRemoteAddr());
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            http.setAttribute(FORWARDED_ATTRIBUTE, forwarded);
        }
    }
}
