package com.omnissa.access.approval.model;

import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A multi-stage approval chain definition (#53) — admin-managed, like
 * {@link AutoRule}. {@link #appPattern}/{@link #groupName} decide which
 * incoming requests get routed through this chain instead of the ordinary
 * single-decision flow; matched the same way an {@code AutoRule} MATCH rule
 * is (see {@code RuleEngine.matchesCriteria}, empty selects nothing).
 *
 * <p>A request matched to a chain is exempt from auto-approval/rejection
 * rules entirely — a chain exists specifically to require sequential human
 * judgment, so letting a MATCH rule auto-decide it on arrival would defeat
 * the point.
 *
 * <p>Deliberately no {@code kind}/versioning/scope beyond this — see #53's
 * handoff brief for the open questions (per-stage timeout, chain editor UI)
 * this slice does not attempt to answer.
 */
@Entity
public class ApprovalChain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private boolean enabled = true;

    private String name;

    /** Exact app name or '*' wildcard, case-insensitive. Nullable. */
    @Nullable
    private String appPattern;

    /** Matches a value in the callout's userAttributes.groupNames. Nullable. */
    @Nullable
    private String groupName;

    public ApprovalChain() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Nullable public String getAppPattern() { return appPattern; }
    public void setAppPattern(@Nullable String appPattern) { this.appPattern = appPattern; }

    @Nullable public String getGroupName() { return groupName; }
    public void setGroupName(@Nullable String groupName) { this.groupName = groupName; }
}
