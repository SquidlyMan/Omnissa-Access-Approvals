package com.omnissa.access.approval.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

@Entity
public class CalloutRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String MEDIA_TYPE_NAME = "application/vnd.vmware.horizon.manager.approval.responseMessage+json";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Nonnull
    private CalloutOperation operation;

    @Nonnull
    private String requestId;

    @Nonnull
    private String resourceUuid;

    @Nullable
    private String resourceName;

    @Nonnull
    private String userId;

    @Nullable
    private String userDeviceId;

    @Nullable
    @Lob
    private HashMap<String, List<String>> userAttributes;

    @Nullable
    private String userDeviceName;

    @Nullable
    @Transient
    private HashMap<String, String> deviceProperties;

    @Nullable
    private String notes;

    @Nullable
    @Transient
    private List<String> categories;

    @Temporal(value = TemporalType.TIMESTAMP)
    private Date receivedDate = new Date();

    @Temporal(value = TemporalType.TIMESTAMP)
    private Date responseDate;

    @Nullable
    private String responseMessage;

    /** Admin (or "system"/rule) that decided this request. */
    @Nullable
    private String decidedBy;

    private String state;

    // --- JIT / time-bound access (#49). All nullable → null = permanent grant. ---

    /** Granted access duration in minutes; null = permanent (default behavior). */
    @Nullable
    private Integer accessTtlMinutes;

    /** When the grant expires (= approval time + TTL). The expiry sweep revokes past this. */
    @Nullable
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date accessExpiresAt;

    /** When the scheduler removed the entitlement in Access. */
    @Nullable
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date revokedAt;

    /**
     * The requester's Access SCIM id, resolved and stored at approval time. The
     * inbound callout only carries a numeric userId that Access cannot map back
     * to a SCIM id, so we capture it once (when granting) and reuse it to add the
     * exclusion at expiry. Nullable = not yet/again resolved.
     */
    @Nullable
    private String scimUserId;

    public CalloutRequest() {}

    public CalloutRequest(@Nonnull String userId) {
        this.userId = userId;
    }

    @JsonCreator
    public CalloutRequest(
            @Nonnull  @JsonProperty("operation")       final CalloutOperation operation,
            @Nonnull  @JsonProperty("requestId")       final String requestId,
            @Nonnull  @JsonProperty("resourceUuid")    final String resourceUuid,
            @Nullable @JsonProperty("resourceName")    final String resourceName,
            @Nonnull  @JsonProperty("userId")          final String userId,
            @Nullable @JsonProperty("userAttributes")  final HashMap<String, List<String>> userAttributes,
            @Nullable @JsonProperty("userDeviceId")    final String userDeviceId,
            @Nullable @JsonProperty("userDeviceName")  final String userDeviceName,
            @Nullable @JsonProperty("deviceProperties") final HashMap<String, String> deviceProperties,
            @Nullable @JsonProperty("notes")           final String notes,
            @Nullable @JsonProperty("categories")      final List<String> categories) {
        this.operation = operation;
        this.requestId = requestId;
        this.resourceUuid = resourceUuid;
        this.resourceName = resourceName;
        this.userId = userId;
        this.userAttributes = userAttributes;
        this.deviceProperties = deviceProperties;
        this.userDeviceId = userDeviceId;
        this.userDeviceName = userDeviceName;
        this.notes = notes;
        this.categories = categories;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Nonnull public CalloutOperation getOperation() { return operation; }
    @Nonnull public String getRequestId() { return requestId; }
    @Nonnull public String getResourceUuid() { return resourceUuid; }
    @Nullable public String getResourceName() { return resourceName; }
    @Nonnull public String getUserId() { return userId; }
    @Nullable public HashMap<String, List<String>> getUserAttributes() { return userAttributes; }
    @Nullable public String getUserDeviceId() { return userDeviceId; }
    @Nullable public String getUserDeviceName() { return userDeviceName; }
    @Nullable public HashMap<String, String> getDeviceProperties() { return deviceProperties; }
    @Nullable public String getNotes() { return notes; }
    @Nullable public List<String> getCategories() { return categories; }

    public Date getReceivedDate() { return receivedDate; }
    public void setReceivedDate(Date receivedDate) { this.receivedDate = receivedDate; }

    public Date getResponseDate() { return responseDate; }
    public void setResponseDate(Date responseDate) { this.responseDate = responseDate; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    @Nullable public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(@Nullable String responseMessage) { this.responseMessage = responseMessage; }

    @Nullable public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(@Nullable String decidedBy) { this.decidedBy = decidedBy; }

    @Nullable public Integer getAccessTtlMinutes() { return accessTtlMinutes; }
    public void setAccessTtlMinutes(@Nullable Integer accessTtlMinutes) { this.accessTtlMinutes = accessTtlMinutes; }

    @Nullable public Date getAccessExpiresAt() { return accessExpiresAt; }
    public void setAccessExpiresAt(@Nullable Date accessExpiresAt) { this.accessExpiresAt = accessExpiresAt; }

    @Nullable public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(@Nullable Date revokedAt) { this.revokedAt = revokedAt; }

    @Nullable public String getScimUserId() { return scimUserId; }
    public void setScimUserId(@Nullable String scimUserId) { this.scimUserId = scimUserId; }

    @Override
    public String toString() {
        return "CalloutRequest{id=" + id + ", operation=" + operation + ", requestId='" + requestId +
                "', resourceName='" + resourceName + "', userId='" + userId + "', state='" + state + "'}";
    }
}
