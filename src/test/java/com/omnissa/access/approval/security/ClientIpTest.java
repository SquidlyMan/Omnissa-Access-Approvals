package com.omnissa.access.approval.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client address used to key rate limits and login throttles (#70).
 *
 * <p>The rule under test is that a caller cannot choose their own key. Every
 * one of these fails against the previous implementation, which returned the
 * first {@code X-Forwarded-For} entry — a value supplied by whoever sent the
 * request.
 */
class ClientIpTest {

    private static final String PEER = "10.88.88.1";

    /** A request as it arrives at the filter, before anything rewrites it. */
    private MockHttpServletRequest arriving(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/approvals/new");
        request.setRemoteAddr(PEER);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        // Exactly what the listener does when the request enters the container.
        ClientAddressFilter.capture(request);
        return request;
    }

    @Test
    @DisplayName("with no trusted proxies, a forged header cannot change the key")
    void forgedHeaderIsIgnoredByDefault() {
        String first = ClientIp.of(arriving("203.0.113.9"), 0);
        String second = ClientIp.of(arriving("198.51.100.4"), 0);

        assertThat(first)
                .as("the caller set X-Forwarded-For and must not thereby pick their own "
                        + "rate-limit bucket")
                .isEqualTo(PEER);
        assertThat(second).isEqualTo(PEER);
        assertThat(first)
                .as("two requests differing only in a header they control must share a key, "
                        + "or the limit counts nothing")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("one proxy in front: the entry that proxy wrote is used")
    void singleTrustedProxy() {
        // Our proxy appended the peer it saw. Anything left of that is hearsay.
        assertThat(ClientIp.of(arriving("203.0.113.9, 198.51.100.7"), 1))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("two proxies in front: counting continues from the right")
    void twoTrustedProxies() {
        assertThat(ClientIp.of(arriving("203.0.113.9, 198.51.100.7"), 2))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("a caller who pads the header cannot push their value into the trusted slot")
    void paddingTheChainDoesNotHelp() {
        // One real proxy. The caller sends three fabricated entries hoping the
        // count lands on one of them; the arithmetic is from the right, so it
        // still lands on what the proxy appended.
        assertThat(ClientIp.of(arriving("1.1.1.1, 2.2.2.2, 3.3.3.3, 198.51.100.7"), 1))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("a chain shorter than configured falls back to the peer")
    void shortChainFallsBack() {
        // Configured for two proxies but only one entry present: this request
        // did not come the expected way, so nothing in the header is believed.
        assertThat(ClientIp.of(arriving("203.0.113.9"), 2)).isEqualTo(PEER);
        assertThat(ClientIp.of(arriving(null), 2)).isEqualTo(PEER);
    }

    @Test
    @DisplayName("junk in the trusted position is rejected rather than used as a key")
    void junkIsRejected() {
        assertThat(ClientIp.of(arriving("203.0.113.9, not-an-address"), 1)).isEqualTo(PEER);
        assertThat(ClientIp.of(arriving("203.0.113.9, "), 1)).isEqualTo(PEER);
    }

    @Test
    @DisplayName("IPv6 in the trusted position is preserved")
    void ipv6Survives() {
        assertThat(ClientIp.of(arriving("203.0.113.9, 2001:db8::1"), 1))
                .isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("getRemoteAddr is NOT a safe fallback: the framework rewrites it from the header")
    void remoteAddrIsItselfForged() throws Exception {
        // The reason ClientAddressFilter has to capture the peer early, kept as a
        // test so that a future change to server.forward-headers-strategy shows
        // up here rather than silently restoring the vulnerability.
        ForwardedHeaderFilter framework = new ForwardedHeaderFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/approvals/new");
        request.setRemoteAddr(PEER);
        request.addHeader("X-Forwarded-For", "203.0.113.9, 198.51.100.7");

        AtomicReference<String> afterFramework = new AtomicReference<>();
        framework.doFilter(request, new MockHttpServletResponse(),
                (rq, rs) -> afterFramework.set(((HttpServletRequest) rq).getRemoteAddr()));

        assertThat(afterFramework.get())
                .as("if this ever equals the real peer, forwarded-header handling changed and "
                        + "the early capture may no longer be necessary — but until then, "
                        + "getRemoteAddr() returns the caller's own claim")
                .isEqualTo("203.0.113.9")
                .isNotEqualTo(PEER);
    }
}
