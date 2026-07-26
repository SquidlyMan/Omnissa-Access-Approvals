package com.omnissa.access.approval.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit-trail export shares this escaping with the request export. Audit
 * messages are free text and routinely contain commas, quotes and newlines, so
 * a break here would corrupt the exported record silently.
 */
class CsvSharedEscapingTest {

    @Test
    void nullBecomesEmptyString() {
        assertThat(Csv.field(null)).isEmpty();
    }

    @Test
    void plainValuePassesThroughUnquoted() {
        assertThat(Csv.field("approved")).isEqualTo("approved");
    }

    @Test
    void commaForcesQuoting() {
        assertThat(Csv.field("Approved for 5 minutes, re-requestable"))
                .isEqualTo("\"Approved for 5 minutes, re-requestable\"");
    }

    @Test
    void quotesAreDoubledAndWrapped() {
        assertThat(Csv.field("Revoked \"I Am Showcase\" access"))
                .isEqualTo("\"Revoked \"\"I Am Showcase\"\" access\"");
    }

    @Test
    void newlinesForceQuoting() {
        assertThat(Csv.field("line one\nline two")).isEqualTo("\"line one\nline two\"");
        assertThat(Csv.field("line one\rline two")).isEqualTo("\"line one\rline two\"");
    }
}
