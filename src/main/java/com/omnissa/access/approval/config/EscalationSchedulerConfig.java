package com.omnissa.access.approval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A thread pool that belongs to escalation alone (#51).
 *
 * <p><strong>Why this exists, when nothing else in the tool has one.</strong>
 * Every other {@code @Scheduled} job — JIT expiry, JIT restore, the expiry-rule
 * sweep — shares Spring's single default scheduler thread. That is fine while
 * those jobs make only bounded, fire-and-forget calls, and {@code
 * SchedulerHeartbeat} exists precisely because one wedged job silently stops
 * the rest: if JIT expiry stalls, time-bound access simply never expires while
 * the container is up, the UI works and every health check stays green.
 *
 * <p>Escalation is the first job that must make <em>real, answer-bearing</em>
 * network calls: it resolves the approver pool from Omnissa Access over SCIM
 * and pushes notifications, and it needs the result synchronously so a failed
 * delivery can leave the stage un-advanced and be retried rather than being
 * recorded as a summons that never happened. On the shared thread those two
 * requirements are in direct conflict — a slow tenant would block JIT expiry
 * for as long as the calls take.
 *
 * <p>Giving escalation its own single-threaded pool resolves the conflict
 * instead of trading one risk for the other: sends stay synchronous and
 * retryable, while a slow or unreachable tenant can only ever delay
 * escalation itself. Pool size is deliberately 1, so escalation is still
 * serialized against itself and two sweeps can never overlap on the same
 * request.
 */
@Configuration
public class EscalationSchedulerConfig {

    public static final String ESCALATION_SCHEDULER = "escalationScheduler";

    @Bean(name = ESCALATION_SCHEDULER, destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler escalationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("escalation-");
        // Do not hold shutdown open on a tenant that has stopped answering:
        // the calls are already bounded by the REST client's 5s timeouts, and
        // an escalation is safe to abandon — the next sweep re-fires it,
        // because the stage only advances once delivery is accounted for.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
