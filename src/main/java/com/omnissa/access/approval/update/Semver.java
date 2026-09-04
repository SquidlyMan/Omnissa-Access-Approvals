package com.omnissa.access.approval.update;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A three-part release version, compared numerically.
 *
 * <p>Numeric comparison is the whole reason this type exists. Sorted as
 * strings, the registry's published patch tags put {@code 1.9.5} above
 * {@code 1.21.1} — twelve minor versions behind the real newest — and a checker
 * built on string ordering would report that and never fire again. The trap is
 * live in the registry today, not hypothetical.
 *
 * <p>Only the immutable {@code major.minor.patch} shape parses. The moving
 * tags CI also publishes — {@code 1.21}, {@code latest}, {@code sha-…} — are
 * deliberately rejected, because a deploy pinned to one of them is not pinned.
 */
public record Semver(int major, int minor, int patch) implements Comparable<Semver> {

    private static final Pattern RELEASE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private static final Comparator<Semver> ORDER = Comparator
            .comparingInt(Semver::major)
            .thenComparingInt(Semver::minor)
            .thenComparingInt(Semver::patch);

    /** Empty for anything that is not exactly {@code N.N.N}. */
    public static Optional<Semver> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = RELEASE.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Semver(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public static boolean isRelease(String text) {
        return parse(text).isPresent();
    }

    @Override
    public int compareTo(Semver other) {
        return ORDER.compare(this, other);
    }

    public boolean isNewerThan(Semver other) {
        return compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
