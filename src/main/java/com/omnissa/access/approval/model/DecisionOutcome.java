package com.omnissa.access.approval.model;

/**
 * Result of processing an approval decision — either delivering it (PUT) to
 * Omnissa Access, or, for a multi-stage chain (#53), advancing it internally
 * without contacting Access at all.
 */
public enum DecisionOutcome {

    /** Access accepted the decision; the local request is approved/rejected. */
    DELIVERED,

    /**
     * Access answered 4xx — the request no longer exists there. The local
     * request has been moved to the "expired" state.
     */
    EXPIRED,

    /**
     * Access was unreachable or answered 5xx — transient. The local request
     * is left pending so the decision can be retried.
     */
    UNREACHABLE,

    /**
     * An approval on a chained request (#53) that was not the chain's final
     * stage. Nothing was sent to Access — {@code currentStage} advanced and
     * the request stays pending, now awaiting the next stage's decision. A
     * rejection at any stage never produces this outcome: it always falls
     * through to {@code DELIVERED}/{@code EXPIRED}/{@code UNREACHABLE}
     * immediately, by design.
     */
    STAGE_ADVANCED
}
