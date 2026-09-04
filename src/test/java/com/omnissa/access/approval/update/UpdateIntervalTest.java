package com.omnissa.access.approval.update;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A bad interval must degrade, never fail startup or hammer the registry. */
class UpdateIntervalTest {

    @Test
    void validDurationsPassThrough() {
        assertEquals(Duration.ofDays(1), UpdateInterval.resolve("P1D"));
        assertEquals(Duration.ofHours(6), UpdateInterval.resolve(" PT6H "));
        assertEquals(Duration.ofHours(6).toMillis(), new UpdateInterval("PT6H").millis());
    }

    @Test
    void garbageFallsBackToTheDefault() {
        assertEquals(UpdateInterval.DEFAULT, UpdateInterval.resolve("abc"));
        assertEquals(UpdateInterval.DEFAULT, UpdateInterval.resolve("1d"));
        assertEquals(UpdateInterval.DEFAULT, UpdateInterval.resolve(""));
        assertEquals(UpdateInterval.DEFAULT, UpdateInterval.resolve(null));
    }

    @Test
    void tooSmallIsRaisedToTheMinimum() {
        assertEquals(UpdateInterval.MINIMUM, UpdateInterval.resolve("PT1S"));
        assertEquals(UpdateInterval.MINIMUM, UpdateInterval.resolve("PT0S"));
        assertEquals(UpdateInterval.MINIMUM, UpdateInterval.resolve("PT5M"));
    }
}
