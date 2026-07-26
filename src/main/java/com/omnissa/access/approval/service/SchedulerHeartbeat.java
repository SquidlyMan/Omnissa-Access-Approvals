package com.omnissa.access.approval.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records when each scheduled job last completed.
 *
 * <p>This is the highest-value health signal in the tool, because it is the only
 * failure that is otherwise <em>completely silent</em>. All scheduled jobs share
 * Spring's default single-threaded scheduler, so one wedged job stops the rest.
 * If the JIT sweeps stop, time-bound access simply never expires: the container
 * is up, the UI works, every other check is green, and users quietly keep
 * entitlements they should have lost. Nothing surfaces it — which is precisely
 * why it has to be measured.
 */
@Service
public class SchedulerHeartbeat {

    /** JIT sweeps run every minute; five missed cycles is a real stall, not a slow tick. */
    public static final Duration JIT_STALE_AFTER = Duration.ofMinutes(5);

    /** The expiry-rule sweep runs hourly, so it tolerates far more drift. */
    public static final Duration HOURLY_STALE_AFTER = Duration.ofHours(3);

    public static final String JIT_EXPIRY = "jitExpiry";
    public static final String JIT_RESTORE = "jitRestore";
    public static final String EXPIRY_RULES = "expiryRules";

    private final Map<String, Instant> lastRun = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    public void recordRun(String job) {
        lastRun.put(job, Instant.now());
    }

    /**
     * A job is stale once it has not completed within its tolerance. Before a
     * job's first run the grace period is measured from startup, so a freshly
     * started container is not reported as degraded during its initial delay.
     */
    public boolean isStale(String job, Duration tolerance) {
        Instant reference = lastRun.getOrDefault(job, startedAt);
        return Duration.between(reference, Instant.now()).compareTo(tolerance) > 0;
    }

    public boolean anyStale() {
        return isStale(JIT_EXPIRY, JIT_STALE_AFTER)
                || isStale(JIT_RESTORE, JIT_STALE_AFTER)
                || isStale(EXPIRY_RULES, HOURLY_STALE_AFTER);
    }

    /** Per-job detail for the authenticated health endpoint. */
    public Map<String, Object> detail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(JIT_EXPIRY, describe(JIT_EXPIRY, JIT_STALE_AFTER));
        detail.put(JIT_RESTORE, describe(JIT_RESTORE, JIT_STALE_AFTER));
        detail.put(EXPIRY_RULES, describe(EXPIRY_RULES, HOURLY_STALE_AFTER));
        return detail;
    }

    private Map<String, Object> describe(String job, Duration tolerance) {
        Instant last = lastRun.get(job);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("lastRun", last != null ? last.toString() : null);
        entry.put("ageSeconds", Duration.between(last != null ? last : startedAt, Instant.now()).toSeconds());
        entry.put("toleranceSeconds", tolerance.toSeconds());
        entry.put("stale", isStale(job, tolerance));
        return entry;
    }
}
