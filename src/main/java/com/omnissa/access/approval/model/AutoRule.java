package com.omnissa.access.approval.model;

import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * An automatic approval/rejection rule. Two kinds:
 * - MATCH rule: appPattern and/or groupName set, expiryDays null —
 *   evaluated when a new activation request arrives.
 * - EXPIRY rule: expiryDays set (action must be "reject") — evaluated
 *   hourly by the scheduler against stale pending requests.
 */
@Entity
public class AutoRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean enabled = true;

    /** "approve" or "reject". */
    private String action;

    /** Exact app name or '*' wildcards, case-insensitive. Nullable. */
    @Nullable
    private String appPattern;

    /** Matches a value in the callout's userAttributes.groupNames. Nullable. */
    @Nullable
    private String groupName;

    /** Auto-reject requests pending longer than this many days. Nullable. */
    @Nullable
    private Integer expiryDays;

    /**
     * For "approve" MATCH rules: grant time-bound (JIT) access for this many
     * minutes (#49). Null = permanent grant (default). Ignored for reject rules.
     */
    @Nullable
    private Integer grantTtlMinutes;

    public AutoRule() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    @Nullable public String getAppPattern() { return appPattern; }
    public void setAppPattern(@Nullable String appPattern) { this.appPattern = appPattern; }

    @Nullable public String getGroupName() { return groupName; }
    public void setGroupName(@Nullable String groupName) { this.groupName = groupName; }

    @Nullable public Integer getExpiryDays() { return expiryDays; }
    public void setExpiryDays(@Nullable Integer expiryDays) { this.expiryDays = expiryDays; }

    @Nullable public Integer getGrantTtlMinutes() { return grantTtlMinutes; }
    public void setGrantTtlMinutes(@Nullable Integer grantTtlMinutes) { this.grantTtlMinutes = grantTtlMinutes; }

    @Override
    public String toString() {
        return "AutoRule{id=" + id + ", enabled=" + enabled + ", action='" + action +
                "', appPattern='" + appPattern + "', groupName='" + groupName +
                "', expiryDays=" + expiryDays + ", grantTtlMinutes=" + grantTtlMinutes + "}";
    }
}
