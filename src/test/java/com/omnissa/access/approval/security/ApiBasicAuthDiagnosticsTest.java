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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A rejected callout must say why, in terms of what the caller sent.
 *
 * <p>Every rejection previously logged the same sentence, so "Omnissa Access
 * never saved any credentials" and "Access saved the wrong ones" were
 * indistinguishable — and those need opposite fixes. This came out of a real
 * incident where requests stopped arriving and the log could not say which had
 * happened.
 *
 * <p>The other half of the contract is what must NOT appear: the configured
 * password, and the presented one. These logs ship to syslog.
 */
class ApiBasicAuthDiagnosticsTest {

    private static final String USER = "omnissa-access-callout";
    private static final String PASSWORD = "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";   // 64 hex, as generated

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

    private String reject(String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/approvals/new");
        request.setRemoteAddr("35.163.252.224");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        new ApiBasicAuthFilter(USER, PASSWORD)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no Authorization header at all names the actual cause")
    void noHeader() throws Exception {
        assertThat(reject(null))
                .contains("no Authorization header")
                .contains("OMNISSA_API_USERNAME");
    }

    @Test
    @DisplayName("a truncated password reports both lengths")
    void truncatedPassword() throws Exception {
        // The failure a console with a shorter field limit produces.
        String message = reject(basic(USER, PASSWORD.substring(0, 20)));
        assertThat(message)
                .contains("username '" + USER + "' matched")
                .contains("password was 20 characters, expected 64")
                .contains("truncated");
    }

    @Test
    @DisplayName("a right-length but wrong password is called a transcription error, not truncation")
    void wrongButCorrectLength() throws Exception {
        assertThat(reject(basic(USER, PASSWORD.replace('0', '9'))))
                .contains("expected length (64)")
                .contains("transcription");
    }

    @Test
    @DisplayName("a wrong username is named, since it is not a secret")
    void wrongUsername() throws Exception {
        assertThat(reject(basic("admin", PASSWORD)))
                .contains("username 'admin' did NOT match");
    }

    @Test
    @DisplayName("a non-Basic scheme is identified rather than reported as missing")
    void wrongScheme() throws Exception {
        assertThat(reject("Bearer abc.def.ghi"))
                .contains("Bearer")
                .contains("expects Basic");
    }

    @Test
    @DisplayName("no password, presented or configured, ever reaches the log")
    void secretsAreNeverLogged() throws Exception {
        for (String header : new String[]{
                null,
                basic(USER, PASSWORD.substring(0, 20)),
                basic(USER, PASSWORD.replace('0', '9')),
                basic("admin", PASSWORD)}) {
            appender.list.clear();
            String message = reject(header);
            assertThat(message)
                    .as("the configured password must never be logged — these go to syslog")
                    .doesNotContain(PASSWORD);
            assertThat(message)
                    .as("nor any substantial run of it")
                    .doesNotContain(PASSWORD.substring(0, 20));
            assertThat(message)
                    .as("nor a presented password, even a wrong one")
                    .doesNotContain(PASSWORD.replace('0', '9'));
        }
    }
}
