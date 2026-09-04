package com.omnissa.access.approval.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rollback floor (#83, acceptance criterion 15). */
class RollbackFloorTest {

    private static Semver v(String s) { return Semver.parse(s).orElseThrow(); }

    @Test
    @DisplayName("1.19.5 itself and everything above it are at or above the floor")
    void atOrAboveFloor() {
        assertThat(RollbackFloor.isBelowFloor(v("1.19.5"))).isFalse();
        assertThat(RollbackFloor.isBelowFloor(v("1.21.1"))).isFalse();
        assertThat(RollbackFloor.reopenedBy(v("1.21.1"))).isEmpty();
        assertThat(RollbackFloor.reopenedBy(v("1.19.9"))).isEmpty();
    }

    @Test
    @DisplayName("1.19.5 to 1.19.8 are above the floor but still told about the exempt probe — a break, not a hole")
    void betweenFloorAndProbeFix() {
        assertThat(RollbackFloor.isBelowFloor(v("1.19.5"))).isFalse();
        assertThat(RollbackFloor.reopenedBy(v("1.19.5")))
                .hasSize(1)
                .allMatch(r -> r.contains("OPTIONS probe") && r.contains("401"))
                .noneMatch(r -> r.contains("unauthenticated"));
    }

    @Test
    @DisplayName("below the floor names exactly what each step down reopens")
    void reopenedIsCumulative() {
        assertThat(RollbackFloor.isBelowFloor(v("1.19.4"))).isTrue();
        // 1.19.4 is below 1.19.9 and 1.19.5, but not below 1.16.1
        assertThat(RollbackFloor.reopenedBy(v("1.19.4")))
                .hasSize(2)
                .anyMatch(r -> r.contains("OPTIONS probe"))
                .anyMatch(r -> r.contains("unauthenticated"))
                .noneMatch(r -> r.contains("Slack approver map"));
        // 1.5.0 reopens all three
        assertThat(RollbackFloor.reopenedBy(v("1.5.0"))).hasSize(3)
                .anyMatch(r -> r.contains("Slack approver map"));
    }
}
