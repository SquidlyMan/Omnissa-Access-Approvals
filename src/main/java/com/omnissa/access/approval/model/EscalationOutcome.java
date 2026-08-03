package com.omnissa.access.approval.model;

/**
 * Result of firing an escalation (#51).
 *
 * <p>This exists because the five older {@code WebhookNotifier.notify*}
 * methods return early and <em>silently</em> when {@code webhook.url} is
 * blank — a fully supported configuration. Inheriting that would mark every
 * request escalated while nothing was actually sent, and because each stage
 * fires exactly once by design, no retry would ever follow. So escalation
 * reports what really happened instead, mirroring {@code DecisionOutcome}
 * and {@code RevokeOutcome}.
 */
public enum EscalationOutcome {

    /** At least one channel accepted it — the stage may advance. */
    SENT,

    /**
     * Nothing is configured to receive it (no webhook URL, no resolvable
     * approver). The stage still advances: there is nothing to retry, and
     * leaving it un-advanced would re-attempt every sweep forever.
     */
    NOT_CONFIGURED,

    /**
     * Something was configured but delivery failed. The stage is left
     * un-advanced so the next sweep retries — exactly as the JIT sweeps
     * leave {@code UNREACHABLE}.
     */
    FAILED
}
