package com.omnissa.access.approval.model.security;

public enum AuthorityName {
    /** Users, rules, tenant config, log bundle — plus everything below. */
    ROLE_ADMIN,
    /** Decide requests: approve, reject, revoke, block, allow re-request. */
    ROLE_APPROVER,
    /** Read the queue, audit trail and statistics. Changes nothing. */
    ROLE_VIEWER,
    /** Audit trail and CSV export only — no live queue, no decisions. */
    ROLE_AUDITOR,
    /**
     * Legacy. Predates the role model and was never enforced; treated as
     * equivalent to {@link #ROLE_VIEWER}. Retained so existing USER_AUTHORITY
     * rows still deserialize.
     */
    ROLE_USER
}
