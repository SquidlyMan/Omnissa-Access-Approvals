package com.omnissa.access.approval.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Last-known outcome of webhook delivery.
 *
 * <p>Slack and Teams incoming webhooks cannot be actively probed — there is no
 * ping, and "testing" one means posting to the channel. So health here is
 * necessarily retrospective: what happened the last time the tool actually sent
 * something.
 *
 * <p><strong>A successful POST proves less than it appears to.</strong> Slack
 * posts the message synchronously, so a 200 does mean it arrived. Power Automate
 * returns <em>202 Accepted</em>, meaning the trigger was queued — the flow may
 * still fail, or drop the payload, long after the tool has recorded success.
 * That is exactly how the Teams payload-shape bug hid for weeks: every dropped
 * message logged as sent. Treat a green notifications component as "the endpoint
 * accepted our request", not "the message reached the channel".
 */
@Service
public class NotificationHealth {

    /** Transient failures happen; a persistent misconfiguration does not fix itself. */
    public static final int DEGRADED_AFTER_CONSECUTIVE_FAILURES = 3;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant lastSuccess;
    private volatile Instant lastFailure;
    private volatile String lastError;

    public void recordSuccess() {
        lastSuccess = Instant.now();
        consecutiveFailures.set(0);
    }

    public void recordFailure(String error) {
        lastFailure = Instant.now();
        lastError = error;
        consecutiveFailures.incrementAndGet();
    }

    public boolean isDegraded() {
        return consecutiveFailures.get() >= DEGRADED_AFTER_CONSECUTIVE_FAILURES;
    }

    /** True once anything has been attempted — before that there is nothing to report. */
    public boolean hasActivity() {
        return lastSuccess != null || lastFailure != null;
    }

    public Map<String, Object> detail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lastSuccess", lastSuccess != null ? lastSuccess.toString() : null);
        detail.put("lastFailure", lastFailure != null ? lastFailure.toString() : null);
        detail.put("lastError", lastError);
        detail.put("consecutiveFailures", consecutiveFailures.get());
        detail.put("note", "A successful send means the endpoint accepted the request. "
                + "Power Automate returns 202 Accepted (queued), so Teams delivery to the "
                + "channel is not confirmed by this check.");
        return detail;
    }
}
