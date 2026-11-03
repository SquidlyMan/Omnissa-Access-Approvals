package com.omnissa.access.approval.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CSV export is opened in a spreadsheet, and a cell that begins with
 * {@code =}, {@code +}, {@code -} or {@code @} runs there as a formula. The
 * exported fields include the application name, the requester and the
 * approver's note — callout- and approver-supplied text.
 */
class CsvFormulaTest {

    @Test
    @DisplayName("a text value that would start a formula is prefixed with an apostrophe")
    void formulaStartersAreNeutralised() {
        assertThat(Csv.field("=1+1")).isEqualTo("'=1+1");
        assertThat(Csv.field("+44 20")).isEqualTo("'+44 20");
        assertThat(Csv.field("-see note")).isEqualTo("'-see note");
        assertThat(Csv.field("@everyone")).isEqualTo("'@everyone");
        assertThat(Csv.field("\tindented")).isEqualTo("'\tindented");
    }

    @Test
    @DisplayName("a formula that also needs RFC-4180 quoting gets both")
    void prefixedAndQuoted() {
        assertThat(Csv.field("=HYPERLINK(\"https://evil.example\",\"open\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"open\"\")\"");
    }

    @Test
    @DisplayName("ordinary text passes through as before")
    void ordinaryValuesAreUntouched() {
        assertThat(Csv.field("Salesforce")).isEqualTo("Salesforce");
        assertThat(Csv.field("dean@example.com")).isEqualTo("dean@example.com");
        assertThat(Csv.field("2026-11-03T12:00")).isEqualTo("2026-11-03T12:00");
        assertThat(Csv.field("")).isEmpty();
    }

    @Test
    @DisplayName("a negative number is a number, not a formula")
    void numbersAreNotPrefixed() {
        assertThat(Csv.field(-5)).isEqualTo("-5");
        assertThat(Csv.field(-5.5)).isEqualTo("-5.5");
    }
}
