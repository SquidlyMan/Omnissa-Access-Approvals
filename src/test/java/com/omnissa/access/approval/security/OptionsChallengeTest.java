package com.omnissa.access.approval.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Whether the OPTIONS probe is challenged (#70 follow-up).
 *
 * <p>Omnissa Access sends an unauthenticated {@code OPTIONS} when its approvals
 * settings are saved, so the probe is exempt by default — without that, the
 * settings cannot be saved at all.
 *
 * <p>That exemption is also the <em>only</em> place this endpoint departs from
 * the original {@code vidm-approval} reference implementation, which ran Spring
 * Boot 1.4 with {@code spring-boot-starter-security} on the classpath and no
 * security configuration. In Boot 1.x that means {@code SecurityAutoConfiguration}
 * secures every request with HTTP Basic — the probe included. If Access uses the
 * probe to decide whether this endpoint needs credentials, exempting it teaches
 * Access that none are required, which matches the observed behaviour: the
 * tenant holds a username and password, the probe succeeds, and callouts arrive
 * with no {@code Authorization} header.
 *
 * <p>A hypothesis under test, so it is a flag and it is off by default. These
 * tests pin both behaviours so neither can change by accident.
 */
class OptionsChallengeTest {

    private static final String USER = "ApprovalTool";
    private static final String PASSWORD = "PALWFa3jdaopK8Z76wL";

    private MockHttpServletResponse options(boolean challenge, String authHeader, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/approvals/new");
        request.setRemoteAddr("35.163.252.224");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiBasicAuthFilter(USER, PASSWORD, challenge).doFilter(request, response, chain);
        return response;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("default: the probe passes unauthenticated, so Access can save its settings")
    void probeExemptByDefault() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = options(false, null, chain);

        assertThat(response.getStatus())
                .as("challenging the probe by default would stop the approvals settings being "
                        + "saved at all, which is a worse failure than the one under investigation")
                .isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("flag on: the probe is challenged, exactly as Boot 1.x would have")
    void probeChallengedWhenEnabled() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = options(true, null, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("the challenge is the whole point — it is what would tell Access "
                        + "credentials are required")
                .isEqualTo("Basic realm=\"approval-api\"");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("flag on: an authenticated probe still succeeds")
    void authenticatedProbeSucceedsWhenEnabled() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = options(true, basic(USER, PASSWORD), chain);

        assertThat(response.getStatus())
                .as("if Access learns to authenticate, its probe must then work — otherwise "
                        + "the experiment traps the settings permanently")
                .isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("with no credentials configured the probe passes regardless of the flag")
    void openEndpointIsUnaffected() throws Exception {
        for (boolean challenge : new boolean[]{false, true}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest request =
                    new MockHttpServletRequest("OPTIONS", "/api/approvals/new");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ApiBasicAuthFilter("", "", challenge).doFilter(request, response, chain);

            assertThat(response.getStatus())
                    .as("the flag must not invent authentication where none is configured")
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("POST is unaffected by the flag — it was always challenged")
    void postBehaviourUnchanged() throws Exception {
        for (boolean challenge : new boolean[]{false, true}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/approvals/new");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ApiBasicAuthFilter(USER, PASSWORD, challenge).doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
        }
    }
}
