package com.omnissa.access.approval.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What makes a local password acceptable.
 *
 * <p>Deliberately <strong>no composition rules</strong> — no "must contain an
 * uppercase letter, a digit and a symbol". Those requirements are widely
 * discouraged (NIST SP 800-63B) because they push people towards predictable
 * shapes like {@code Password1!} while adding little real entropy, and they
 * block genuinely strong passphrases. Length plus a check against obviously
 * weak values is more effective and less irritating.
 *
 * <p>What is enforced instead:
 * <ul>
 *   <li><strong>Length</strong> — 12 minimum. This is a standing credential for
 *       an interface that can revoke entitlements in a live tenant.</li>
 *   <li><strong>Variety</strong> — {@code aaaaaaaaaaaa} is twelve characters and
 *       would otherwise pass.</li>
 *   <li><strong>Not derived from the username</strong> — the one string an
 *       attacker always knows.</li>
 *   <li><strong>Not a well-known password</strong>, and not a straight run of
 *       sequential characters.</li>
 * </ul>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 200;

    /** Twelve identical characters is length without strength. */
    private static final int MIN_DISTINCT_CHARACTERS = 5;

    /**
     * The values actually tried first. Not a substitute for a breach corpus —
     * a full check would mean calling an external service, which a self-hosted
     * tool should not require to change a password — but it catches the cases
     * that matter most for a small deployment.
     */
    private static final Set<String> WELL_KNOWN = Set.of(
            "password", "password1", "password123", "passw0rd", "p@ssw0rd", "p@ssword",
            "administrator", "administrator1", "adminadmin", "admin123456",
            "123456789012", "1234567890123", "111111111111", "000000000000",
            "qwertyuiop12", "qwertyuiopas", "letmein12345", "welcome12345",
            "changeme1234", "iloveyou1234", "monkey123456", "dragon123456",
            "abcdefghijkl", "aaaaaaaaaaaa", "secretpassword", "trustno1234");

    private PasswordPolicy() {
    }

    /**
     * @return a human explanation of the first problem found, or {@code null}
     *         when the password is acceptable. A single clear reason beats a
     *         list of unmet rules.
     */
    public static String validate(String password, String username) {
        if (password == null || password.isBlank()) {
            return "Enter a password.";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters. "
                    + "A passphrase of a few unrelated words is easier to remember and stronger "
                    + "than a short complex one.";
        }
        if (password.length() > MAX_LENGTH) {
            return "Password must be at most " + MAX_LENGTH + " characters.";
        }

        String lower = password.toLowerCase(Locale.ROOT);

        if (password.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            return "Password repeats too few characters. Use a longer, more varied passphrase.";
        }
        if (WELL_KNOWN.contains(lower)) {
            return "That password is well known and among the first an attacker tries.";
        }
        if (isSequentialRun(lower)) {
            return "Password is a simple sequence of characters. Use something less predictable.";
        }
        if (username != null && username.length() >= 3
                && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return "Password must not contain the username.";
        }
        return null;
    }

    /** True when every character steps by one, e.g. {@code abcdefghijkl} or {@code 987654321098}. */
    private static boolean isSequentialRun(String value) {
        if (value.length() < MIN_LENGTH) {
            return false;
        }
        int direction = Integer.signum(value.charAt(1) - value.charAt(0));
        if (direction == 0) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) - value.charAt(i - 1) != direction) {
                return false;
            }
        }
        return true;
    }

    /** For surfacing the rules in the UI without duplicating the wording. */
    public static List<String> describe() {
        return List.of(
                "At least " + MIN_LENGTH + " characters",
                "Not a well-known password or a simple sequence",
                "Must not contain the username",
                "No uppercase/digit/symbol requirement — a passphrase is fine");
    }
}
