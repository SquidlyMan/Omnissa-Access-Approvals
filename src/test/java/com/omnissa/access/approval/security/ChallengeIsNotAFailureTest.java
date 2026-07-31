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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A challenge is not a failure, and must not be logged as one.
 *
 * <p>Omnissa Access does not send credentials preemptively. Every callout starts
 * with an unauthenticated attempt, collects the {@code 401}, and is retried with
 * credentials — often from a different egress address, because Access delivers
 * from several nodes. The bare first attempt is half of a working handshake.
 *
 * <p>Logging it as a fault was itself the defect. The message asserted
 * <em>"its approvals settings have no credentials saved"</em> — false on a
 * correctly configured tenant, emitted on every callout, and the direct cause of
 * hours spent investigating the reverse proxy, HTTP Digest and field truncation.
 * A log line that states a cause it has not established is worse than one that
 * says nothing at all.
 *
 * <p>So the warning is now earned rather than assumed: it appears when
 * credentials are presented and are wrong, or when challenges go unanswered
 * often enough that no handshake is happening.
 */
class ChallengeIsNotAFailureTest {

    private static final String USER = "ApprovalTool";
    private static final String PASSWORD = "PALWFa3jdaopK8Z76wL";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private ApiBasicAuthFilter filter;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ApiBasicAuthFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        filter = new ApiBasicAuthFilter(USER, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void post(String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/approvals/new");
        request.setRemoteAddr("35.163.252.224");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("the first leg of a working handshake does not warn")
    void handshakeFirstLegIsNotAWarning() throws Exception {
        post(basic(USER, PASSWORD));   // a callout authenticates
        appender.list.clear();
        post(null);                    // the next one starts bare, as Access always does

        assertThat(warnings())
                .as("this fires on every callout of a correctly configured tenant; saying "
                        + "'no credentials saved' there is simply false")
                .isEmpty();
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("first leg of HTTP Basic");
    }

    @Test
    @DisplayName("wrong credentials always warn, however healthy things looked")
    void wrongCredentialsAlwaysWarn() throws Exception {
        post(basic(USER, PASSWORD));
        appender.list.clear();
        // Same length as the real one, so the message reports a mismatch rather
        // than truncation — the point here is that it warns at all.
        post(basic(USER, "XALWFa3jdaopK8Z76wL"));

        assertThat(warnings())
                .as("a presented credential that does not match is never routine")
                .isNotEmpty();
        assertThat(warnings().get(0).getFormattedMessage()).contains("did not match");
    }

    @Test
    @DisplayName("with no successful authentication ever, the very first bare request warns")
    void neverAuthenticatedWarnsImmediately() throws Exception {
        post(null);

        assertThat(warnings())
                .as("nothing has ever authenticated, so this is a real configuration problem "
                        + "and the operator needs to know on the first request")
                .isNotEmpty();
        assertThat(warnings().get(0).getFormattedMessage())
                .contains("no Authorization header was sent");
    }

    @Test
    @DisplayName("repeated unanswered challenges stop being treated as a handshake")
    void persistentSilenceEventuallyWarns() throws Exception {
        post(basic(USER, PASSWORD));
        appender.list.clear();

        // A handshake is one bare attempt then a credentialed retry. Bare
        // attempt after bare attempt with no retry is something else.
        post(null);
        post(null);
        post(null);

        assertThat(warnings())
                .as("the caller is not answering the challenge, which is worth knowing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a later success resets the count, so a quiet period does not accumulate")
    void successResetsTheCount() throws Exception {
        post(basic(USER, PASSWORD));
        post(null);
        post(basic(USER, PASSWORD));   // handshake completed
        appender.list.clear();
        post(null);                    // next callout, first leg again

        assertThat(warnings())
                .as("ordinary traffic must never accumulate towards a warning")
                .isEmpty();
    }
}
