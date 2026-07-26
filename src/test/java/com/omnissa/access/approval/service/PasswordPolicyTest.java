package com.omnissa.access.approval.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local password rules (#58).
 *
 * <p>No composition requirements by design — those push people towards
 * {@code Password1!} and block genuinely strong passphrases. Length plus
 * rejection of obviously weak values does more, so these tests are mostly about
 * what gets through rather than what is demanded.
 */
class PasswordPolicyTest {

    private PasswordPolicy policy;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        policy = new PasswordPolicy();
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "configuredMinLength", 12);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "minDistinct", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "blockUsername", true);
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "blocklistFile", "");
        policy.load();
    }


    @Test
    void aPassphraseIsAcceptedWithoutSymbolsOrDigits() {
        // The case composition rules would wrongly reject.
        assertNull(policy.validate("correct horse battery staple", "dean"));
        assertNull(policy.validate("rainy tuesday harbour", "dean"));
    }

    @Test
    void tooShortIsRejectedWithAdviceRatherThanAScolding() {
        String message = policy.validate("short1", "dean");
        assertNotNull(message);
        assertTrue(message.contains("12"), message);
        assertTrue(message.toLowerCase().contains("passphrase"),
                "the message should point somewhere useful: " + message);
    }

    /** Twelve characters of nothing. The gap that motivated a real policy. */
    @Test
    void lengthAloneIsNotEnough() {
        assertNotNull(policy.validate("aaaaaaaaaaaa", "dean"));
        assertNotNull(policy.validate("ababababababab", "dean"));
    }

    @Test
    void wellKnownPasswordsAreRejectedRegardlessOfCase() {
        assertNotNull(policy.validate("password123", "dean"));
        assertNotNull(policy.validate("Password123", "dean"));
        assertNotNull(policy.validate("P@SSW0RD", "dean"));
    }

    @Test
    void straightSequencesAreRejected() {
        assertNotNull(policy.validate("abcdefghijkl", "dean"));
        assertNotNull(policy.validate("123456789012", "dean"));
    }

    @Test
    void aSequenceEmbeddedInSomethingLongerIsFine() {
        // Only a whole-string run is a sequence; "abcdef" inside a real
        // passphrase should not be penalised.
        assertNull(policy.validate("harbour-abcdef-tuesday", "dean"));
    }

    @Test
    void thePasswordMustNotContainTheUsername() {
        assertNotNull(policy.validate("dean-is-my-name-here", "dean"));
        assertNotNull(policy.validate("XXDEANXXharbourXX", "dean"));
        assertNull(policy.validate("harbour tuesday rain", "dean"));
    }

    @Test
    void shortUsernamesDoNotBlockEverything() {
        // A two-letter username would otherwise reject most passphrases
        // containing those letters in sequence.
        assertNull(policy.validate("harbour tuesday rain", "jo"));
    }

    @Test
    void nullsAndBlanksAreHandled() {
        assertNotNull(policy.validate(null, "dean"));
        assertNotNull(policy.validate("   ", "dean"));
        assertNull(policy.validate("harbour tuesday rain", null));
    }

    @Test
    void absurdlyLongInputIsRejectedRatherThanHashed() {
        assertNotNull(policy.validate("a".repeat(500) + "bcdef", "dean"));
    }

    // ── Configurability (#62) ────────────────────────────────────────────────

    private PasswordPolicy configured(java.util.Map<String, Object> settings) {
        PasswordPolicy p = new PasswordPolicy();
        var defaults = new java.util.HashMap<String, Object>(java.util.Map.of(
                "configuredMinLength", 12, "minDistinct", 5, "blockUsername", true,
                "blocklistFile", "", "requireMixedCase", false,
                "requireDigit", false, "requireSymbol", false));
        defaults.putAll(settings);
        defaults.forEach((k, v) -> org.springframework.test.util.ReflectionTestUtils.setField(p, k, v));
        p.load();
        return p;
    }

    /**
     * Configuration may tighten the policy, never remove it. Without the floor,
     * min-length=1 would silently make the break-glass credential worthless.
     */
    @Test
    void minLengthIsClampedToTheFloor() {
        assertEquals(PasswordPolicy.ABSOLUTE_MIN_LENGTH,
                configured(java.util.Map.of("configuredMinLength", 1)).minLength());
        assertEquals(20, configured(java.util.Map.of("configuredMinLength", 20)).minLength());
    }

    @Test
    void aLoweredMinimumIsHonouredAboveTheFloor() {
        PasswordPolicy relaxed = configured(java.util.Map.of("configuredMinLength", 8));
        assertNull(relaxed.validate("harbourtue", "dean"));
    }

    @Test
    void compositionRulesAreOffByDefaultButWorkWhenEnabled() {
        assertNull(policy.validate("correct horse battery staple", "dean"));

        PasswordPolicy strict = configured(java.util.Map.of(
                "requireMixedCase", true, "requireDigit", true, "requireSymbol", true));
        assertNotNull(strict.validate("correct horse battery staple", "dean"));
        assertNull(strict.validate("Correct horse battery staple 1!", "dean"));
    }

    /**
     * The reason the bundled list targets LONG weak passwords: only ten of the
     * ten thousand most common passwords reach twelve characters, so a general
     * corpus is nearly redundant here. These pass every composition rule and are
     * caught only by the list.
     */
    @Test
    void longButWeakPasswordsAreRejected() {
        assertNotNull(policy.validate("passwordpassword", "dean"));
        assertNotNull(policy.validate("qwertyuiopasdf", "dean"));
        assertNotNull(policy.validate("letmeinletmein", "dean"));

        PasswordPolicy strict = configured(java.util.Map.of(
                "requireMixedCase", true, "requireDigit", true, "requireSymbol", true));
        assertNotNull(strict.validate("Passwordpassword", "dean"),
                "composition rules do not catch a doubled word");
    }

    @Test
    void theBundledListLoads() {
        assertTrue(policy.blocklistSize() > 50,
                "the bundled weak-password list should be present on the classpath");
    }

    @Test
    void anUnreadableBlocklistFileDegradesRatherThanFailing() {
        PasswordPolicy p = configured(java.util.Map.of("blocklistFile", "/nonexistent/path.txt"));
        assertTrue(p.blocklistSize() > 50, "the bundled list must still apply");
        assertNull(p.validate("harbour tuesday rain", "dean"));
    }

    @Test
    void describeReflectsTheActiveConfiguration() {
        assertTrue(policy.describe().stream().anyMatch(r -> r.contains("12")));
        assertTrue(policy.describe().stream().anyMatch(r -> r.contains("No uppercase")));

        PasswordPolicy strict = configured(java.util.Map.of("requireDigit", true));
        assertTrue(strict.describe().stream().anyMatch(r -> r.contains("digit")));
        assertFalse(strict.describe().stream().anyMatch(r -> r.contains("No uppercase")));
    }
}
