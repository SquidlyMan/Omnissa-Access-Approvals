package com.omnissa.access.approval.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The login-page notice truth table, exactly as documented. */
class LoginNoticeTest {

    @Test
    @DisplayName("nothing set → the disclaimer")
    void defaultIsTheDisclaimer() {
        assertThat(LoginNotice.resolve(false, "", "")).isSameAs(LoginNotice.DISCLAIMER);
        assertThat(LoginNotice.resolve(false, null, null)).isSameAs(LoginNotice.DISCLAIMER);
    }

    @Test
    @DisplayName("disable=false and no notice → still the disclaimer")
    void explicitFalseKeepsTheDisclaimer() {
        assertThat(LoginNotice.resolve(false, "", "   ")).isSameAs(LoginNotice.DISCLAIMER);
    }

    @Test
    @DisplayName("disable=true → nothing, even with a custom notice set")
    void disableWins() {
        assertThat(LoginNotice.resolve(true, "", "")).isSameAs(LoginNotice.NONE);
        assertThat(LoginNotice.resolve(true, "Monitored", "All access is logged.")).isSameAs(LoginNotice.NONE);
    }

    @Test
    @DisplayName("a custom notice replaces the disclaimer, with its title")
    void customReplacesTheDisclaimer() {
        LoginNotice n = LoginNotice.resolve(false, "Authorised use only", "All access is logged.");
        assertThat(n.kind()).isEqualTo("custom");
        assertThat(n.title()).isEqualTo("Authorised use only");
        assertThat(n.text()).isEqualTo("All access is logged.");
    }

    @Test
    @DisplayName("a blank title becomes 'Notice'; a literal \\n becomes a line break; nothing else is interpreted")
    void titleDefaultAndLineBreaks() {
        LoginNotice n = LoginNotice.resolve(false, "  ", "Line one\\nLine two <b>not html</b>  ");
        assertThat(n.title()).isEqualTo(LoginNotice.DEFAULT_TITLE);
        assertThat(n.text()).isEqualTo("Line one\nLine two <b>not html</b>");
    }
}
