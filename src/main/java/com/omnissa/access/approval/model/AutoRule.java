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

    /**
     * Escalation (#51), carried on the EXPIRY rule rather than in a separate
     * policy object: one rule reads "nudge at 4 hours, reject at 3 days".
     * Mature tools keep escalation policies separate because many services
     * reference one policy; a tenant here has one expiry rule, so a second
     * table and CRUD page would be more configuration, not less.
     *
     * <p>Nudge after this many minutes pending; null = escalation off.
     */
    @Nullable
    private Integer escalateAfterMinutes;

    /**
     * Auto-release an unactioned claim/assignment after this many minutes;
     * null = inherit {@link #escalateAfterMinutes}.
     *
     * <p>A claim that lapses cannot rot. The failure this prevents is
     * specific: an approver claims at 17:00 and goes home, a second approver
     * sees the owner badge, reads it as handled, and does nothing — an
     * abandoned claim is a <em>worse</em> signal than no claim at all.
     */
    @Nullable
    private Integer claimTtlMinutes;

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

    @Nullable public Integer getEscalateAfterMinutes() { return escalateAfterMinutes; }
    public void setEscalateAfterMinutes(@Nullable Integer escalateAfterMinutes) { this.escalateAfterMinutes = escalateAfterMinutes; }

    @Nullable public Integer getClaimTtlMinutes() { return claimTtlMinutes; }
    public void setClaimTtlMinutes(@Nullable Integer claimTtlMinutes) { this.claimTtlMinutes = claimTtlMinutes; }

    /** Effective claim TTL: explicit value, else the escalation interval. Null = no auto-release. */
    @Nullable
    public Integer effectiveClaimTtlMinutes() {
        return claimTtlMinutes != null ? claimTtlMinutes : escalateAfterMinutes;
    }

    @Override
    public String toString() {
        return "AutoRule{id=" + id + ", enabled=" + enabled + ", action='" + action +
                "', appPattern='" + appPattern + "', groupName='" + groupName +
                "', expiryDays=" + expiryDays + ", grantTtlMinutes=" + grantTtlMinutes + "}";
    }
}
