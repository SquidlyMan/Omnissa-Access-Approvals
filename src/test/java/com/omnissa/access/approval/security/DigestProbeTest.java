package com.omnissa.access.approval.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Digest listening post: offer the scheme, observe the answer, never accept it.
 *
 * <p>Omnissa Access holds credentials for the callout, receives a {@code Basic}
 * challenge and answers with nothing — observed on the wire, not inferred. A
 * client that performs only Digest behaves exactly that way, so offering Digest
 * distinguishes "will not use Basic" from "sends no credentials at all".
 *
 * <p>The safety property is the important one and comes first below: a Digest
 * response must <strong>never</strong> authenticate. Verifying Digest correctly
 * needs nonce tracking, replay windows and {@code qop} handling; anything less
 * is a hole. This is an instrument, and instruments must not become doors.
 */
class DigestProbeTest {

    private static final String USER = "ApprovalTool";
    private static final String PASSWORD = "PALWFa3jdaopK8Z76wL";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(ApiBasicAuthFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private MockHttpServletResponse call(boolean probeOn, String authHeader, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/approvals/new");
        request.setRemoteAddr("35.163.252.224");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiBasicAuthFilter(USER, PASSWORD, probeOn).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("SAFETY: a Digest response never authenticates, even a well-formed one")
    void digestIsNeverAccepted() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = call(true,
                "Digest username=\"" + USER + "\", realm=\"approval-api\", "
                        + "nonce=\"abc123\", uri=\"/api/approvals/new\", qop=auth, nc=00000001, "
                        + "cnonce=\"xyz\", response=\"6629fae49393a05397450978507c4ef1\", "
                        + "algorithm=MD5",
                chain);

        assertThat(response.getStatus())
                .as("accepting Digest without verifying the nonce would be an open door")
                .isEqualTo(401);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("SAFETY: correct Basic credentials still authenticate while the probe is on")
    void basicStillWorksWithTheProbeOn() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (USER + ":" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = call(true, basic, chain);

        assertThat(response.getStatus())
                .as("the probe must not disturb the working path")
                .isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("off by default: only Basic is advertised")
    void probeOffAdvertisesBasicOnly() throws Exception {
        MockHttpServletResponse response = call(false, null, mock(FilterChain.class));
        List<String> challenges = response.getHeaders("WWW-Authenticate");
        assertThat(challenges).containsExactly("Basic realm=\"approval-api\"");
    }

    @Test
    @DisplayName("probe on: Basic is offered first, Digest second")
    void probeOnOffersBothBasicFirst() throws Exception {
        MockHttpServletResponse response = call(true, null, mock(FilterChain.class));
        List<String> challenges = response.getHeaders("WWW-Authenticate");

        assertThat(challenges).hasSize(2);
        assertThat(challenges.get(0))
                .as("Basic must come first so a client taking the first acceptable scheme "
                        + "keeps working exactly as it does today")
                .startsWith("Basic ");
        assertThat(challenges.get(1)).startsWith("Digest ").contains("qop=\"auth\"")
                .contains("algorithm=MD5");
    }

    @Test
    @DisplayName("each challenge carries a fresh nonce")
    void nonceIsNotReused() throws Exception {
        String first = call(true, null, mock(FilterChain.class)).getHeaders("WWW-Authenticate").get(1);
        String second = call(true, null, mock(FilterChain.class)).getHeaders("WWW-Authenticate").get(1);
        assertThat(first)
                .as("a fixed nonce would make the probe look like a replayable challenge")
                .isNotEqualTo(second);
    }

    @Test
    @DisplayName("a Digest answer is reported unmistakably, without the response hash")
    void digestAnswerIsReported() throws Exception {
        call(true, "Digest username=\"" + USER + "\", realm=\"approval-api\", nonce=\"abc\", "
                + "qop=auth, algorithm=MD5, response=\"6629fae49393a05397450978507c4ef1\"",
                mock(FilterChain.class));

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("ANSWERED THE DIGEST CHALLENGE")
                .contains("username='" + USER + "'")
                .contains("algorithm=MD5")
                .contains("response present=true");
        assertThat(message)
                .as("the response hash is derived from the password and must not be logged")
                .doesNotContain("6629fae49393a05397450978507c4ef1");
    }
}
