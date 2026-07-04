package com.omnissa.access.approval.model;

/**
 * Result of delivering an approval decision (PUT) to Omnissa Access.
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
    UNREACHABLE
}
