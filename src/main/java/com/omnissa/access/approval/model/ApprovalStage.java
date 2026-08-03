package com.omnissa.access.approval.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * One ordered stage of an {@link ApprovalChain} (#53). A plain FK column
 * (no JPA relationship), matching how the rest of this codebase's entities
 * are related.
 *
 * <p>{@link #approverType} is {@code "ROLE"}, {@code "GROUP"} or
 * {@code "USER"} — a plain String, not an enum, for the same
 * additive-schema reason {@code CalloutRequest.state} is a String.
 *
 * <ul>
 *   <li>{@code ROLE} — {@link #approverValue} is an authority name (e.g.
 *       {@code ROLE_APPROVER}); eligibility is a cheap local check against
 *       the acting session's granted authorities, no Access call.</li>
 *   <li>{@code GROUP} — {@link #approverValue} is an Access group id (the
 *       same id space as {@code OMNISSA_ROLE_MAP}, verified 2026-08-02);
 *       eligibility is a live {@code AccessGroupService.resolveMembers}
 *       check. Local (non-OIDC) accounts can never satisfy a GROUP stage —
 *       they carry no Access group membership to check, so this fails
 *       closed for them by design, not by omission.</li>
 *   <li>{@code USER} — {@link #approverValue} names one individual, matched
 *       against the acting session's own identity (preferred_username,
 *       email or subject for an OIDC user; the username for a local
 *       account). Unlike a GROUP stage this works for local accounts, and
 *       it needs no Access call to decide eligibility.</li>
 * </ul>
 *
 * <p><strong>A USER stage is the narrowest thing here, and that cuts both
 * ways.</strong> It is the only stage type that can be satisfied by exactly
 * one person, so it is also the only one that goes undecidable when that
 * person leaves, changes their sign-in identity, or is simply away. An
 * administrator can always decide any stage, which is what stops that being
 * a dead end — but prefer a GROUP or ROLE stage wherever a team rather than
 * a named person is really what is meant.
 *
 * <p>No per-stage timeout/escalation in this slice — a stuck stage is
 * covered only by the existing whole-request expiry auto-rule, exactly as
 * an unstaged pending request is today. Deliberately deferred: see #53's
 * handoff brief, which asks whether a stage timeout should reuse #51's
 * escalation mechanism rather than duplicate it — that question isn't
 * answered here.
 */
@Entity
public class ApprovalStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chainId;

    /** 1-based order within the chain. */
    private int stageOrder;

    /** {@code "ROLE"} or {@code "GROUP"}. */
    private String approverType;

    /** A {@code ROLE_*} authority name, or an Access group id, per {@link #approverType}. */
    private String approverValue;

    public ApprovalStage() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }

    public int getStageOrder() { return stageOrder; }
    public void setStageOrder(int stageOrder) { this.stageOrder = stageOrder; }

    public String getApproverType() { return approverType; }
    public void setApproverType(String approverType) { this.approverType = approverType; }

    public String getApproverValue() { return approverValue; }
    public void setApproverValue(String approverValue) { this.approverValue = approverValue; }
}
