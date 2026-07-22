package com.omnissa.access.approval.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private static MockHttpServletRequest post(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/api/approvals/new");
        req.setRemoteAddr(ip);
        return req;
    }

    @Test
    void underLimitPassesThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(post("10.0.0.1"), res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void overLimitReturns429Json() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("10.0.0.2"), new MockHttpServletResponse(), chain);
        filter.doFilter(post("10.0.0.2"), new MockHttpServletResponse(), chain);

        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(post("10.0.0.2"), res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentType()).isEqualTo("application/json");
        assertThat(res.getContentAsString()).contains("rate limit exceeded");
        // Chain only advanced for the two allowed requests.
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void optionsAlwaysPassesEvenOverLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = post("10.0.0.3");
            req.setMethod("OPTIONS");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        verify(chain, times(5)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void limitZeroDisablesRateLimiting() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(0);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 100; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(post("10.0.0.4"), res, chain);
            assertThat(res.getStatus()).isEqualTo(200);
        }
        verify(chain, times(100)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void xForwardedForFirstValueIsUsedAsClientKey() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);

        // Both requests share the same remoteAddr but different first XFF value —
        // they must be counted as separate clients, so neither is rejected.
        MockHttpServletRequest r1 = post("192.168.0.1");
        r1.addHeader("X-Forwarded-For", "203.0.113.1, 192.168.0.1");
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(r1, res1, chain);

        MockHttpServletRequest r2 = post("192.168.0.1");
        r2.addHeader("X-Forwarded-For", "203.0.113.2, 192.168.0.1");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(r2, res2, chain);

        assertThat(res1.getStatus()).isEqualTo(200);
        assertThat(res2.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sameXForwardedForClientIsRateLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest r1 = post("192.168.0.1");
        r1.addHeader("X-Forwarded-For", "203.0.113.9");
        filter.doFilter(r1, new MockHttpServletResponse(), chain);

        MockHttpServletRequest r2 = post("192.168.0.2"); // different socket, same XFF
        r2.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(r2, res2, chain);

        assertThat(res2.getStatus()).isEqualTo(429);
    }

    @Test
    void separateIpsCountedSeparately() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse resA = new MockHttpServletResponse();
        filter.doFilter(post("10.1.1.1"), resA, chain);
        MockHttpServletResponse resB = new MockHttpServletResponse();
        filter.doFilter(post("10.2.2.2"), resB, chain);

        assertThat(resA.getStatus()).isEqualTo(200);
        assertThat(resB.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
