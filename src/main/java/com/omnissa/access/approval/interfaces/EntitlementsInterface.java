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
     * Revoke the user's entitlement to the requested app. Resolves the user's
     * SCIM id from the app's entitlement listing (the user is entitled once the
     * request was approved) and DELETEs it.
     */
    RevokeOutcome revokeAccess(CalloutRequest request);
}
