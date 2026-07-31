package com.omnissa.access.approval.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The client address to key rate limits and login throttles on.
 *
 * <p>There was one of these, copied into three places — {@code RateLimitFilter},
 * {@code LoginThrottleFilter} and {@code SecurityConfig} — and all three took
 * the <em>first</em> {@code X-Forwarded-For} entry. A proxy <strong>appends</strong>
 * to that header, so the leftmost value is the one the caller sent. Varying it
 * per request produced a fresh bucket every time: the callout rate limit and,
 * more seriously, the brute-force throttle protecting the break-glass local
 * admin password, were both bypassed by setting a header.
 *
 * <p>Counting from the <em>right</em> is what makes it trustworthy. The rightmost
 * entry was written by the proxy nearest this application, about a peer it
 * genuinely observed; each step further left is one more hop of hearsay, until
 * the leftmost value, which is simply asserted by the caller. So with N proxies
 * in front, the furthest entry that can be believed is N from the right.
 *
 * <p>The default is <strong>zero</strong> — believe nothing in the header and use
 * the socket peer. That is the safe answer when the deployment is unknown, and
 * it degrades honestly: behind an unconfigured proxy every request keys to the
 * proxy's address, so limits become shared rather than forgeable. Shared limits
 * are a nuisance; forgeable limits are not limits.
 *
 * @see ClientAddressFilter for why the peer has to be captured early
 */
public final class ClientIp {

    private static final Logger logger = LoggerFactory.getLogger(ClientIp.class);

    /** Deliberately permissive: a shape check to keep junk out of map keys. */
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f.:\\[\\]]{2,45}");

    /** So the chain is reported once per start, not once per request. */
    private static final AtomicBoolean CHAIN_REPORTED = new AtomicBoolean();

    private ClientIp() {
    }

    /**
     * Logs the first forwarded chain seen, and what the current setting selects
     * from it.
     *
     * <p>The right value for {@code OMNISSA_TRUSTED_PROXY_HOPS} is a property of
     * the deployment, not of this application: it depends on how many proxies
     * append to the header before a request arrives, which cannot be known from
     * here and should not be guessed. Counting the entries in a real request is
     * the only reliable way, so the application reports one and the operator
     * counts.
     */
    private static void reportChainOnce(String forwarded, int hops, String selected) {
        if (!CHAIN_REPORTED.compareAndSet(false, true)) {
            return;
        }
        String[] entries = forwarded.split(",");
        logger.info("First forwarded request seen. X-Forwarded-For carried {} entr{}: [{}]. "
                        + "With omnissa.security.trusted-proxy-hops={} the client is recorded as {}. "
                        + "If that is not the caller you expect, set the hop count to the number of "
                        + "proxies in front of this container — counting from the right, each hop is "
                        + "one entry.",
                entries.length, entries.length == 1 ? "y" : "ies",
                forwarded.trim(), hops, selected);
    }

    /**
     * @param trustedProxyHops how many reverse proxies sit in front of this
     *                         application. Zero means trust no forwarded entry.
     */
    public static String of(HttpServletRequest request, int trustedProxyHops) {
        String peer = peerOf(request);
        String forwarded = forwardedOf(request);
        String resolved = resolve(peer, forwarded, trustedProxyHops);

        // Reported after resolving, so the log names the address actually used,
        // and reported whatever the hop count is — a deployment running on the
        // default is precisely the one whose operator needs to see the chain.
        if (forwarded != null && !forwarded.isBlank()) {
            reportChainOnce(forwarded, trustedProxyHops, resolved);
        }
        return resolved;
    }

    private static String resolve(String peer, String forwarded, int trustedProxyHops) {
        if (trustedProxyHops <= 0 || forwarded == null || forwarded.isBlank()) {
            return peer;
        }

        String[] hops = forwarded.split(",");
        // hops.length - trustedProxyHops: with the whole chain ours, this lands
        // on the original caller; with fewer trusted, it stops at the furthest
        // proxy still believed.
        int index = hops.length - trustedProxyHops;
        if (index < 0 || index >= hops.length) {
            // The chain is shorter than configured, so this request did not
            // traverse the expected proxies — someone reached the container by
            // another route. The peer is the only thing left worth believing.
            return peer;
        }

        String candidate = hops[index].trim();
        if (candidate.isEmpty() || !ADDRESS.matcher(candidate).matches()) {
            return peer;
        }
        return candidate;
    }

    private static String peerOf(HttpServletRequest request) {
        Object captured = request.getAttribute(ClientAddressFilter.PEER_ATTRIBUTE);
        if (captured instanceof String peer && !peer.isBlank()) {
            return peer;
        }
        // Only reachable if ClientAddressFilter did not run — a dispatch it is
        // not mapped to, or a unit test. getRemoteAddr() may already have been
        // rewritten from X-Forwarded-For by then, so this is a fallback, not an
        // equivalent.
        return request.getRemoteAddr();
    }

    private static String forwardedOf(HttpServletRequest request) {
        Object captured = request.getAttribute(ClientAddressFilter.FORWARDED_ATTRIBUTE);
        if (captured instanceof String forwarded && !forwarded.isBlank()) {
            return forwarded;
        }
        // The forwarded-header filter strips these once processed, so this only
        // returns anything when it has not run yet.
        return request.getHeader("X-Forwarded-For");
    }
}
