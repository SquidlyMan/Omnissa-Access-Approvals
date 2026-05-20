package com.omnissa.access.approval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

public class CalloutResponse {

    @NotNull
    final String requestId;

    final boolean approved;

    @Nullable
    final String message;

    @JsonCreator
    public CalloutResponse(
            @Nonnull  @JsonProperty("requestId") String requestId,
                      @JsonProperty("approved")  boolean approved,
            @Nullable @JsonProperty("message")   String message) {
        this.requestId = requestId;
        this.approved = approved;
        this.message = message;
    }

    @Nonnull public String getRequestId() { return requestId; }
    public boolean isApproved() { return approved; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "CalloutResponse{requestId='" + requestId + "', approved=" + approved + ", message='" + message + "'}";
    }
}
