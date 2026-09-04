package com.omnissa.access.approval.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version ordering (#83, acceptance criterion 4). The failure this guards
 * against is live in the registry: sorted as strings, its published patch tags
 * put 1.9.5 above 1.21.1.
 */
class SemverTest {

    @Test
    @DisplayName("1.10.0 sorts above 1.9.5 — numerically, not lexically")
    void numericNotLexical() {
        Semver older = Semver.parse("1.9.5").orElseThrow();
        Semver newer = Semver.parse("1.10.0").orElseThrow();
        assertThat(newer.isNewerThan(older)).isTrue();
        assertThat(older.isNewerThan(newer)).isFalse();
        // The string comparison this replaces gets it backwards.
        assertThat("1.10.0".compareTo("1.9.5")).isLessThan(0);
    }

    @Test
    @DisplayName("the newest of the registry's real tags is 1.21.1, not the lexical maximum 1.9.5")
    void newestOfARealisticTagSet() {
        List<String> tags = List.of("1.9.5", "1.19.12", "1.20.0", "1.21.0", "1.21.1", "1.5.0", "1.19.4");
        String newest = tags.stream().map(t -> Semver.parse(t).orElseThrow())
                .max(Comparator.naturalOrder()).orElseThrow().toString();
        assertThat(newest).isEqualTo("1.21.1");
        assertThat(tags.stream().max(Comparator.naturalOrder()).orElseThrow())
                .as("what a string sort would have picked").isEqualTo("1.9.5");
    }

    @Test
    @DisplayName("a major bump is detected (criterion 2)")
    void majorBump() {
        assertThat(Semver.parse("2.0.0").orElseThrow().isNewerThan(Semver.parse("1.21.1").orElseThrow())).isTrue();
    }

    @Test
    @DisplayName("only immutable N.N.N tags parse; moving and commit tags are rejected")
    void onlyReleaseShapeParses() {
        Stream.of("1.21", "latest", "sha-b5caa40", "v1.21.1", "1.21.1-rc1", "", "  ", "1..1")
                .forEach(tag -> assertThat(Semver.parse(tag)).as(tag).isEmpty());
        assertThat(Semver.parse(" 1.21.1 ")).as("surrounding whitespace is tolerated").isPresent();
        assertThat(Semver.isRelease("1.21.1")).isTrue();
    }

    @Test
    @DisplayName("equal versions are neither newer nor older")
    void equality() {
        Semver a = Semver.parse("1.21.1").orElseThrow();
        Semver b = Semver.parse("1.21.1").orElseThrow();
        assertThat(a.isNewerThan(b)).isFalse();
        assertThat(a).isEqualTo(b);
        assertThat(a.toString()).isEqualTo("1.21.1");
    }
}
