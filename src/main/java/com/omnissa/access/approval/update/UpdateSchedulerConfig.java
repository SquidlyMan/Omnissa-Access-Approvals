package com.omnissa.access.approval.update;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A thread pool that belongs to the update check alone.
 *
 * <p>Every {@code @Scheduled} job except escalation shares Spring's single
 * default scheduler thread, and {@code SchedulerHeartbeat} exists because one
 * wedged job silently stops the rest: if JIT expiry stalls, time-bound access
 * never expires while the container is up, the UI works and every health check
 * stays green.
 *
 * <p>The update check calls a third-party registry over the internet. That is
 * exactly the kind of call that can hang — a slow CDN, a stalled TLS handshake,
 * an outage — and it is not a call that has any business delaying the sweep
 * that revokes expired access. Escalation earned its own pool in 1.21.0 for the
 * same reason; this follows that precedent rather than re-arguing it.
 *
 * <p>Pool size 1: two checks never overlap, so the persisted status is written
 * by one thread at a time.
 */
@Configuration
public class UpdateSchedulerConfig {

    public static final String UPDATE_CHECK_SCHEDULER = "updateCheckScheduler";

    @Bean(name = UPDATE_CHECK_SCHEDULER, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler updateCheckScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("update-check-");
        // A check is safe to abandon at shutdown: the next one repeats it, and
        // the registry client's own timeouts already bound the call.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
