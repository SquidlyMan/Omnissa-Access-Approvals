package com.omnissa.access.approval.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a saved request may become as a post-login redirect: a path on this
 * site, or the Dashboard. Pins the line the scanner could not see —
 * {@code java/unvalidated-url-redirection} on the local-login success
 * handler, open since July 2026 — from both sides.
 */
class PostLoginDestinationResolveTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("a saved page on this site is honoured as its path and query, nothing more")
    @CsvSource({
            // Spring Security 6 appends its "continue" marker when it saves a request
            "http://localhost/requests/42?action=approve&continue, /requests/42?action=approve&continue",
            "http://localhost:8081/queue,                         /queue",
            "http://localhost/,                                    /",
            "http://localhost/audit?page=3&size=50,                /audit?page=3&size=50",
            "http://localhost/requests/42?action=approve,          /requests/42?action=approve",
            // the query is opaque data on a same-site path; the fragment never reaches the server
            "http://localhost/queue?next=javascript:alert(1)#x,     /queue?next=javascript:alert(1)",
            // host comparison is case-insensitive, as hostnames are
            "http://LOCALHOST/queue,                               /queue",
    })
    void sameSitePageIsHonoured(String saved, String expected) {
        assertThat(PostLoginDestination.resolve(saved, "localhost")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("anything that is not a plain path on this site lands on the Dashboard")
    @CsvSource(delimiter = '|', value = {
            // another host, however it got there
            "https://evil.example/requests/42?action=approve",
            "http://localhost.evil.example/queue",
            // a scheme-relative URL hiding in the path: //evil.example would leave the site
            "http://localhost//evil.example/queue",
            // a backslash, which browsers read as a slash
            "http://localhost/\\evil.example",
            // a character the path alphabet does not allow
            "http://localhost/queue?next=<script>",
            // not a URL at all
            "::not a url::",
            // nothing to go to
            "http://localhost",
            "mailto:someone@example.com",
    })
    void anythingElseLandsOnTheDashboard(String saved) {
        assertThat(PostLoginDestination.resolve(saved, "localhost")).isEqualTo("/");
    }

    @Test
    @DisplayName("no saved request means the Dashboard")
    void nothingSavedMeansDashboard() {
        assertThat(PostLoginDestination.resolve((String) null, "localhost")).isEqualTo("/");
        assertThat(PostLoginDestination.resolve((org.springframework.security.web.savedrequest.SavedRequest) null,
                "localhost")).isEqualTo("/");
    }
}
