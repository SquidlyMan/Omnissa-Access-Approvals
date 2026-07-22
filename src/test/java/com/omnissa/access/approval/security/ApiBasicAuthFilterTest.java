package com.omnissa.access.approval.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApiBasicAuthFilterTest {

    private static String basic(String user, String pass) {
        String token = Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static MockHttpServletRequest post() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRemoteAddr("10.0.0.1");
        return req;
    }

    @Test
    void blankUsernameIsPassThrough() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(post(), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void nullUsernameIsPassThrough() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter(null, null);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(post(), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void optionsPassesEvenWhenAuthConfigured() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.setMethod("OPTIONS");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void correctCredentialsPass() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.addHeader("Authorization", basic("omnissa", "secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.addHeader("Authorization", basic("omnissa", "wrong"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"approval-api\"");
        assertThat(res.getContentAsString()).contains("unauthorized");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void wrongUsernameIsRejected() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.addHeader("Authorization", basic("intruder", "secret"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void missingAuthorizationHeaderIsRejected() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(post(), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"approval-api\"");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void malformedBase64IsRejected() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.addHeader("Authorization", "Basic !!!not-base64!!!");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void basicSchemeIsCaseInsensitive() throws Exception {
        ApiBasicAuthFilter filter = new ApiBasicAuthFilter("omnissa", "secret");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = post();
        req.addHeader("Authorization", "basic " + Base64.getEncoder()
                .encodeToString("omnissa:secret".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }
}
