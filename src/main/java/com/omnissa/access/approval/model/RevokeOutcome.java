package com.omnissa.access.approval.model;

/**
 * Result of attempting to revoke a JIT (time-bound) entitlement in Omnissa
 * Access (#49). Mirrors {@link DecisionOutcome}'s retry semantics: terminal
 * outcomes finalize the request; UNREACHABLE/ERROR leave it for the next sweep.
 */
public enum RevokeOutcome {
    /** The entitlement was deleted. */
    REVOKED,
    /** The user was not entitled (already removed, or never provisioned) — nothing to do. */
    ALREADY_ABSENT,
    /** Omnissa Access could not be reached — retry next sweep. */
    UNREACHABLE,
    /** Access returned an unexpected error — retry next sweep. */
    ERROR
}
