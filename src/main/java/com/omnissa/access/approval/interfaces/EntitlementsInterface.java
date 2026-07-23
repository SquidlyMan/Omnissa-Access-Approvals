package com.omnissa.access.approval.interfaces;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.RevokeOutcome;

/**
 * Direct manipulation of Omnissa Access entitlements — used by JIT / time-bound
 * access (#49) to withdraw a grant after its TTL expires. This is distinct from
 * the approval callout-response path ({@link ApprovalsInterface}), which can only
 * answer a still-pending request, not revoke access that was already granted.
 */
public interface EntitlementsInterface {

    /**
     * Grant access on approval by removing any per-user exclusion (negative
     * entitlement) for the requested app, so the user's group/user entitlement
     * takes effect. Resolves the requester's SCIM id and returns it so the
     * caller can persist it for the later revoke; returns null if unresolved.
     */
    String grantAccess(CalloutRequest request);

    /**
     * Revoke access on TTL expiry by adding a per-user exclusion (negative
     * entitlement) for the requested app. This blocks the user even when access
     * was granted via a group, without touching the group entitlement.
     */
    RevokeOutcome revokeAccess(CalloutRequest request);
}
