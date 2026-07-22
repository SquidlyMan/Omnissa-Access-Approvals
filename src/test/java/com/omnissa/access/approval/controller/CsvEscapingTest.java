package com.omnissa.access.approval.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApprovalController#csvField(Object)} — RFC-4180 escaping
 * used by the /export.csv handler.
 */
class CsvEscapingTest {

    @Test
    void nullBecomesEmptyString() {
        assertThat(ApprovalController.csvField(null)).isEmpty();
    }

    @Test
    void plainValuePassesThroughUnquoted() {
        assertThat(ApprovalController.csvField("Salesforce")).isEqualTo("Salesforce");
    }

    @Test
    void emptyStringPassesThrough() {
        assertThat(ApprovalController.csvField("")).isEmpty();
    }

    @Test
    void commaTriggersQuoting() {
        assertThat(ApprovalController.csvField("Doe, John")).isEqualTo("\"Doe, John\"");
    }

    @Test
    void quoteIsDoubledAndFieldQuoted() {
        assertThat(ApprovalController.csvField("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void newlineTriggersQuoting() {
        assertThat(ApprovalController.csvField("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void carriageReturnTriggersQuoting() {
        assertThat(ApprovalController.csvField("line1\rline2")).isEqualTo("\"line1\rline2\"");
    }

    @Test
    void combinationOfSpecialCharsHandled() {
        assertThat(ApprovalController.csvField("a,\"b\"\nc"))
                .isEqualTo("\"a,\"\"b\"\"\nc\"");
    }

    @Test
    void nonStringValueUsesToString() {
        assertThat(ApprovalController.csvField(42)).isEqualTo("42");
    }

    @Test
    void nonStringValueWithCommaInToStringIsQuoted() {
        Object value = new Object() {
            @Override public String toString() { return "x,y"; }
        };
        assertThat(ApprovalController.csvField(value)).isEqualTo("\"x,y\"");
    }
}
