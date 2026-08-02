package com.omnissa.access.approval.model.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.util.Date;

/**
 * The last-known Access-sourced directory attributes for a person who has
 * signed in via OIDC, captured at login time.
 *
 * <p>Nothing in this codebase resolves an Access group to its member list —
 * the only way to build a recipient (notify/escalate to a person, per #51 and
 * #53) without that is to remember what someone's own token told us about
 * them the last time they signed in. That is what this table is for. It is
 * necessarily blind to anyone who has never logged in, and stale for anyone
 * whose attributes changed in Access since their last login — both
 * deliberate, documented limits, not oversights.
 *
 * <p>{@link #identity} matches {@code AuditService.currentAdmin()}'s
 * resolution (preferred_username, else email, else subject) so a row here can
 * always be found from an audit actor string, a {@code decidedBy}, or a
 * future {@code assignedOwner} without a second identity scheme.
 */
@Entity
public class DirectoryProfile {

    @Id
    @GeneratedValue
    private Long id;

    @Column(unique = true, nullable = false)
    private String identity;

    /** The OIDC {@code sub} claim — stable even if {@link #identity} later changes. */
    private String subject;

    private String displayName;

    private String email;

    /**
     * The standard OIDC {@code phone_number} claim. Only populated if the
     * tenant's OIDC client requests the {@code phone} scope — not part of
     * this tool's default scope list, so null here is expected unless an
     * operator has added it.
     */
    private String phoneNumber;

    /**
     * Best-effort — Access can sync a mobile number as a directory attribute,
     * but no scope/claim name for it has been verified against a live
     * tenant. Populated only if one of a short list of candidate claim keys
     * is present; null otherwise.
     */
    private String mobilePhone;

    /** Best-effort, same caveat as {@link #mobilePhone}. */
    private String userPrincipalName;

    /** Best-effort, same caveat as {@link #mobilePhone}. */
    private String samAccountName;

    private Date lastLoginAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getMobilePhone() { return mobilePhone; }
    public void setMobilePhone(String mobilePhone) { this.mobilePhone = mobilePhone; }

    public String getUserPrincipalName() { return userPrincipalName; }
    public void setUserPrincipalName(String userPrincipalName) { this.userPrincipalName = userPrincipalName; }

    public String getSamAccountName() { return samAccountName; }
    public void setSamAccountName(String samAccountName) { this.samAccountName = samAccountName; }

    public Date getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Date lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
