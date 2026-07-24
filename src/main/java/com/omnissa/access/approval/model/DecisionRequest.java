package com.omnissa.access.approval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * An admin's approve/reject decision from the UI. Distinct from
 * {@link CalloutResponse} (the payload sent to Omnissa Access) so the JIT
 * {@code ttlMinutes} field never leaks into the Access callout response.
 */
public class DecisionRequest {

    @Nonnull final String requestId;
    final boolean approved;
    @Nullable final String message;
    /** Time-bound access duration in minutes; null = permanent (#49). Applies to approvals only. */
    @Nullable final Integer ttlMinutes;
    /** For timed grants: true (default) = re-requestable after expiry; false = permanent revoke. */
    @Nullable final Boolean reRequestable;

    @JsonCreator
    public DecisionRequest(
            @Nonnull  @JsonProperty("requestId")     String requestId,
                      @JsonProperty("approved")      boolean approved,
            @Nullable @JsonProperty("message")       String message,
            @Nullable @JsonProperty("ttlMinutes")    Integer ttlMinutes,
            @Nullable @JsonProperty("reRequestable") Boolean reRequestable) {
        this.requestId = requestId;
        this.approved = approved;
        this.message = message;
        this.ttlMinutes = ttlMinutes;
        this.reRequestable = reRequestable;
    }

    @Nonnull public String getRequestId() { return requestId; }
    public boolean isApproved() { return approved; }
    @Nullable public String getMessage() { return message; }
    @Nullable public Integer getTtlMinutes() { return ttlMinutes; }
    @Nullable public Boolean getReRequestable() { return reRequestable; }

    /** The Access-facing response, without the JIT TTL. */
    public CalloutResponse toCalloutResponse() {
        return new CalloutResponse(requestId, approved, message);
    }
}
