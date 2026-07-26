package com.omnissa.access.approval.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduler staleness (#44). This is the only failure in the tool with no other
 * outward symptom: all scheduled jobs share Spring's single-threaded scheduler,
 * so if the JIT sweeps wedge, time-bound access silently never expires while
 * everything else stays green.
 */
class SchedulerHeartbeatTest {

    @SuppressWarnings("unchecked")
    private void pretendLastRun(SchedulerHeartbeat heartbeat, String job, Instant when) {
        ((Map<String, Instant>) ReflectionTestUtils.getField(heartbeat, "lastRun")).put(job, when);
    }

    @Test
    void afreshlyStartedContainerIsNotStale() {
        // Jobs have an initial delay; measuring from startup stops a healthy
        // boot being reported as a stall before the first sweep has run.
        assertFalse(new SchedulerHeartbeat().anyStale());
    }

    @Test
    void aJobThatJustRanIsHealthy() {
        SchedulerHeartbeat heartbeat = new SchedulerHeartbeat();
        heartbeat.recordRun(SchedulerHeartbeat.JIT_EXPIRY);

        assertFalse(heartbeat.isStale(SchedulerHeartbeat.JIT_EXPIRY, SchedulerHeartbeat.JIT_STALE_AFTER));
    }

    @Test
    void aWedgedMinutelySweepIsReportedStale() {
        SchedulerHeartbeat heartbeat = new SchedulerHeartbeat();
        pretendLastRun(heartbeat, SchedulerHeartbeat.JIT_EXPIRY, Instant.now().minus(Duration.ofMinutes(6)));

        assertTrue(heartbeat.isStale(SchedulerHeartbeat.JIT_EXPIRY, SchedulerHeartbeat.JIT_STALE_AFTER));
        assertTrue(heartbeat.anyStale());
    }

    /** The hourly sweep must not be judged against the minutely tolerance. */
    @Test
    void theHourlySweepToleratesFarMoreDrift() {
        SchedulerHeartbeat heartbeat = new SchedulerHeartbeat();
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        pretendLastRun(heartbeat, SchedulerHeartbeat.EXPIRY_RULES, tenMinutesAgo);
        // Keep the minutely jobs fresh so anyStale() reflects only the hourly one.
        heartbeat.recordRun(SchedulerHeartbeat.JIT_EXPIRY);
        heartbeat.recordRun(SchedulerHeartbeat.JIT_RESTORE);

        assertFalse(heartbeat.isStale(SchedulerHeartbeat.EXPIRY_RULES, SchedulerHeartbeat.HOURLY_STALE_AFTER));
        assertFalse(heartbeat.anyStale());

        pretendLastRun(heartbeat, SchedulerHeartbeat.EXPIRY_RULES, Instant.now().minus(Duration.ofHours(4)));
        assertTrue(heartbeat.anyStale());
    }

    @Test
    void detailReportsEveryJobWithItsTolerance() {
        SchedulerHeartbeat heartbeat = new SchedulerHeartbeat();
        heartbeat.recordRun(SchedulerHeartbeat.JIT_EXPIRY);

        Map<String, Object> detail = heartbeat.detail();
        assertEquals(3, detail.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> jit = (Map<String, Object>) detail.get(SchedulerHeartbeat.JIT_EXPIRY);
        assertEquals(false, jit.get("stale"));
        assertEquals(SchedulerHeartbeat.JIT_STALE_AFTER.toSeconds(), jit.get("toleranceSeconds"));

        @SuppressWarnings("unchecked")
        Map<String, Object> neverRun = (Map<String, Object>) detail.get(SchedulerHeartbeat.JIT_RESTORE);
        assertEquals(null, neverRun.get("lastRun"), "a job that has not run yet reports no timestamp");
    }

    @Test
    void recordingIsThreadSafe() {
        SchedulerHeartbeat heartbeat = new SchedulerHeartbeat();
        // The scheduler is single-threaded today, but health is read from request
        // threads concurrently — the map must tolerate that.
        assertTrue(ReflectionTestUtils.getField(heartbeat, "lastRun") instanceof ConcurrentHashMap);
    }
}
