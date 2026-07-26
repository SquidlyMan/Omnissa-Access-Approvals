package com.omnissa.access.approval.service;

import org.junit.jupiter.api.Test;

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

    @Test
    void aPassphraseIsAcceptedWithoutSymbolsOrDigits() {
        // The case composition rules would wrongly reject.
        assertNull(PasswordPolicy.validate("correct horse battery staple", "dean"));
        assertNull(PasswordPolicy.validate("rainy tuesday harbour", "dean"));
    }

    @Test
    void tooShortIsRejectedWithAdviceRatherThanAScolding() {
        String message = PasswordPolicy.validate("short1", "dean");
        assertNotNull(message);
        assertTrue(message.contains("12"), message);
        assertTrue(message.toLowerCase().contains("passphrase"),
                "the message should point somewhere useful: " + message);
    }

    /** Twelve characters of nothing. The gap that motivated a real policy. */
    @Test
    void lengthAloneIsNotEnough() {
        assertNotNull(PasswordPolicy.validate("aaaaaaaaaaaa", "dean"));
        assertNotNull(PasswordPolicy.validate("ababababababab", "dean"));
    }

    @Test
    void wellKnownPasswordsAreRejectedRegardlessOfCase() {
        assertNotNull(PasswordPolicy.validate("password123", "dean"));
        assertNotNull(PasswordPolicy.validate("Password123", "dean"));
        assertNotNull(PasswordPolicy.validate("P@SSW0RD", "dean"));
    }

    @Test
    void straightSequencesAreRejected() {
        assertNotNull(PasswordPolicy.validate("abcdefghijkl", "dean"));
        assertNotNull(PasswordPolicy.validate("123456789012", "dean"));
    }

    @Test
    void aSequenceEmbeddedInSomethingLongerIsFine() {
        // Only a whole-string run is a sequence; "abcdef" inside a real
        // passphrase should not be penalised.
        assertNull(PasswordPolicy.validate("harbour-abcdef-tuesday", "dean"));
    }

    @Test
    void thePasswordMustNotContainTheUsername() {
        assertNotNull(PasswordPolicy.validate("dean-is-my-name-here", "dean"));
        assertNotNull(PasswordPolicy.validate("XXDEANXXharbourXX", "dean"));
        assertNull(PasswordPolicy.validate("harbour tuesday rain", "dean"));
    }

    @Test
    void shortUsernamesDoNotBlockEverything() {
        // A two-letter username would otherwise reject most passphrases
        // containing those letters in sequence.
        assertNull(PasswordPolicy.validate("harbour tuesday rain", "jo"));
    }

    @Test
    void nullsAndBlanksAreHandled() {
        assertNotNull(PasswordPolicy.validate(null, "dean"));
        assertNotNull(PasswordPolicy.validate("   ", "dean"));
        assertNull(PasswordPolicy.validate("harbour tuesday rain", null));
    }

    @Test
    void absurdlyLongInputIsRejectedRatherThanHashed() {
        assertNotNull(PasswordPolicy.validate("a".repeat(500) + "bcdef", "dean"));
    }
}
