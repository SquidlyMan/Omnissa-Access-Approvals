package com.omnissa.access.approval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What makes a local password acceptable.
 *
 * <p>Deliberately <strong>no composition rules by default</strong> — no "must
 * contain an uppercase letter, a digit and a symbol". Those are discouraged
 * (NIST SP 800-63B) because they push people towards predictable shapes like
 * {@code Password1!} while adding little real entropy and rejecting strong
 * passphrases. They can be switched on for a compliance requirement, but the
 * default is off and the documentation says why.
 *
 * <p><strong>On the blocklist, and why it is small.</strong> A general
 * common-password corpus is close to useless at this length: of the 10,000 most
 * common passwords, only ten reach twelve characters — the length rule alone
 * rejects the other 9,990. Bundling such a list would add weight while implying
 * protection it does not provide. The built-in list therefore targets the gap
 * that actually exists: values long enough to pass the minimum yet still
 * trivially guessable, such as doubled words, keyboard walks and digit runs.
 *
 * <p>That calculus reverses if {@code min-length} is lowered. Anyone who does so
 * should point {@code blocklist-file} at a real corpus, and the documentation
 * links the two settings for that reason.
 */
@Service
public class PasswordPolicy {

    private static final Logger logger = LoggerFactory.getLogger(PasswordPolicy.class);

    /**
     * Configuration may tighten the policy; it must not be able to remove it.
     * Without a floor, {@code min-length=1} would silently render the
     * break-glass admin credential worthless.
     */
    public static final int ABSOLUTE_MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 200;

    private static final String BUNDLED_LIST = "weak-passwords.txt";

    @Value("${omnissa.password.min-length:12}")
    private int configuredMinLength;

    @Value("${omnissa.password.min-distinct:5}")
    private int minDistinct;

    @Value("${omnissa.password.block-username:true}")
    private boolean blockUsername;

    @Value("${omnissa.password.blocklist-file:}")
    private String blocklistFile;

    @Value("${omnissa.password.require-mixed-case:false}")
    private boolean requireMixedCase;

    @Value("${omnissa.password.require-digit:false}")
    private boolean requireDigit;

    @Value("${omnissa.password.require-symbol:false}")
    private boolean requireSymbol;

    private int minLength;
    private Set<String> blocked = Set.of();

    @PostConstruct
    void load() {
        minLength = Math.max(configuredMinLength, ABSOLUTE_MIN_LENGTH);
        if (configuredMinLength < ABSOLUTE_MIN_LENGTH) {
            logger.warn("omnissa.password.min-length={} is below the {}-character floor and has "
                            + "been raised. Configuration can tighten this policy, not remove it.",
                    configuredMinLength, ABSOLUTE_MIN_LENGTH);
        }

        Set<String> entries = new HashSet<>(readBundled());
        int bundled = entries.size();

        if (blocklistFile != null && !blocklistFile.isBlank()) {
            List<String> extra = readFile(Path.of(blocklistFile.trim()));
            entries.addAll(extra);
            // Logged rather than silent: a mistyped path must not leave an
            // operator believing a corpus is loaded when it is not.
            logger.info("Password blocklist: {} bundled + {} from {} = {} entries",
                    bundled, extra.size(), blocklistFile, entries.size());
        } else {
            logger.info("Password blocklist: {} bundled entries. Set "
                    + "omnissa.password.blocklist-file to add a wordlist — worth doing if you "
                    + "lower omnissa.password.min-length below 12.", bundled);
        }
        blocked = Set.copyOf(entries);
    }

    /**
     * @return a human explanation of the first problem found, or {@code null}
     *         when the password is acceptable. One clear reason beats a list of
     *         unmet rules.
     */
    public String validate(String password, String username) {
        if (password == null || password.isBlank()) {
            return "Enter a password.";
        }
        if (password.length() < minLength) {
            return "Password must be at least " + minLength + " characters. "
                    + "A passphrase of a few unrelated words is easier to remember and stronger "
                    + "than a short complex one.";
        }
        if (password.length() > MAX_LENGTH) {
            return "Password must be at most " + MAX_LENGTH + " characters.";
        }

        String lower = password.toLowerCase(Locale.ROOT);

        if (password.chars().distinct().count() < minDistinct) {
            return "Password repeats too few characters. Use a longer, more varied passphrase.";
        }
        if (blocked.contains(lower)) {
            return "That password is well known and among the first an attacker tries.";
        }
        if (isSequentialRun(lower)) {
            return "Password is a simple sequence of characters. Use something less predictable.";
        }
        if (blockUsername && username != null && username.length() >= 3
                && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return "Password must not contain the username.";
        }

        // Off by default; available for a compliance requirement.
        if (requireMixedCase
                && (password.chars().noneMatch(Character::isUpperCase)
                    || password.chars().noneMatch(Character::isLowerCase))) {
            return "Password must contain both uppercase and lowercase letters.";
        }
        if (requireDigit && password.chars().noneMatch(Character::isDigit)) {
            return "Password must contain a digit.";
        }
        if (requireSymbol && password.chars().allMatch(Character::isLetterOrDigit)) {
            return "Password must contain a symbol.";
        }
        return null;
    }

    /** True when every character steps by one, e.g. {@code abcdefghijkl}. */
    private static boolean isSequentialRun(String value) {
        if (value.length() < 8) {
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

    /** The active rules, for surfacing in the UI without duplicating wording. */
    public List<String> describe() {
        List<String> rules = new ArrayList<>();
        rules.add("At least " + minLength + " characters");
        rules.add("Not a well-known password or a simple sequence");
        if (blockUsername) {
            rules.add("Must not contain the username");
        }
        if (requireMixedCase) {
            rules.add("Must mix uppercase and lowercase");
        }
        if (requireDigit) {
            rules.add("Must contain a digit");
        }
        if (requireSymbol) {
            rules.add("Must contain a symbol");
        }
        if (!requireMixedCase && !requireDigit && !requireSymbol) {
            rules.add("No uppercase/digit/symbol requirement — a passphrase is fine");
        }
        return rules;
    }

    public int minLength() {
        return minLength;
    }

    int blocklistSize() {
        return blocked.size();
    }

    private List<String> readBundled() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(BUNDLED_LIST).getInputStream(), StandardCharsets.UTF_8))) {
            return parse(reader.lines().toList());
        } catch (Exception e) {
            logger.warn("Could not read the bundled weak-password list: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> readFile(Path path) {
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("Could not read password blocklist '{}': {}. Continuing with the bundled "
                    + "list only.", path, e.getMessage());
            return List.of();
        }
    }

    private static List<String> parse(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.toLowerCase(Locale.ROOT))
                .toList();
    }
}
