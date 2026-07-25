package com.omnissa.access.approval.util;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook URLs must be sent to RestTemplate as a {@link URI}, never as a String.
 *
 * <p>A String is treated as a URI <em>template</em> and re-encoded. A Power
 * Automate workflow URL carries an already-encoded, signature-protected query
 * ({@code sp=%2Ftriggers%2Fmanual%2Frun&sig=…}); re-encoding turns {@code %2F}
 * into {@code %252F}, the signature no longer matches, and Power Automate
 * rejects the post with 401 — the Teams notification silently never arrives.
 * This pins the distinction so the String overload cannot creep back in.
 */
class WebhookUrlEncodingTest {

    /** Shape of a real Power Automate "webhook → post card" trigger URL. */
    private static final String POWER_AUTOMATE_URL =
            "https://example.environment.api.powerplatform.com:443/powerautomate/automations/direct"
            + "/cu/13/workflows/abc123/triggers/manual/paths/invoke"
            + "?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=Ab-Cd_1234";

    @Test
    void uriCreatePreservesTheAlreadyEncodedQuery() {
        URI uri = URI.create(POWER_AUTOMATE_URL);

        // The encoded slashes must survive verbatim — this is what the signature covers.
        assertTrue(uri.getRawQuery().contains("sp=%2Ftriggers%2Fmanual%2Frun"),
                "encoded query was altered: " + uri.getRawQuery());
        assertTrue(uri.getRawQuery().contains("sig=Ab-Cd_1234"), uri.getRawQuery());
        assertEquals(POWER_AUTOMATE_URL, uri.toString());
    }

    @Test
    void treatingTheUrlAsATemplateDoubleEncodesIt() {
        // Demonstrates the bug this guards against: the URI-template path that
        // RestTemplate takes for a String argument escapes the existing '%'.
        String templated = UriComponentsBuilder.fromUriString(POWER_AUTOMATE_URL)
                .build()          // parsed, not yet encoded
                .encode()         // what happens to a String URL
                .toUriString();

        assertTrue(templated.contains("%252F"),
                "expected the double-encoding this test documents, got: " + templated);
        assertTrue(!templated.equals(POWER_AUTOMATE_URL),
                "double-encoding should differ from the original URL");
    }
}
