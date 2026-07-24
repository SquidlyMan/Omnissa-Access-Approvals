package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.interfaces.EntitlementsInterface;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.MailNotification;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Shared approve/reject decision flow, so an interactive UI decision and an
 * out-of-band decision (Slack #50, later Teams/email) run through exactly the
 * same path with a consistent, resolved "who decided" attribution (design §3).
 * Delivers the decision to Access, applies JIT grant/TTL, sets `decidedBy`,
 * audits, and fires notifications — identically regardless of channel.
 */
@Service
public class DecisionService {

    @Autowired private ApprovalsInterface approvalsInterface;
    @Autowired private EntitlementsInterface entitlementsInterface;
    @Autowired private ApprovalsRepository approvalsRepository;
    @Autowired private AuditService auditService;
    @Autowired private WebhookNotifier webhookNotifier;
    @Autowired private MailNotification mailNotification;
    @Autowired private SseController sseController;

    /**
     * Deliver a decision and finalize it.
     *
     * @param decider resolved identity of who decided (admin username, or the
     *                mapped Slack approver, …) — used for attribution + decidedBy.
     */
    public DecisionOutcome decide(String requestId, boolean approved, String message,
                                  Integer ttlMinutes, Boolean reRequestable, String decider) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        DecisionOutcome outcome = approvalsInterface.requestResponse(new CalloutResponse(requestId, approved, message));
        switch (outcome) {
            case DELIVERED -> {
                String ttlNote = applyGrant(requestId, approved, ttlMinutes, reRequestable, decider);
                String note = (message != null && !message.isBlank()) ? " — " + message : "";
                auditService.record(approved ? "approved" : "rejected", requestId,
                        request != null ? request.getResourceName() : null,
                        (approved ? "Approved by " : "Rejected by ") + decider + note + ttlNote);
                webhookNotifier.notifyDecision(request, approved, decider, null);
                mailNotification.sendEmailNotification(requestId, approved);
                sseController.publishQueueUpdate("queue-updated");
            }
            case EXPIRED -> {
                auditService.record("decision-undeliverable", requestId,
                        request != null ? request.getResourceName() : null,
                        (approved ? "Approval by " : "Rejection by ") + decider
                                + " could not be delivered — request no longer exists in Omnissa Access");
                webhookNotifier.notifyExpired(request);
                sseController.publishQueueUpdate("queue-updated");
            }
            case UNREACHABLE -> { /* leave pending; caller logs/retries */ }
        }
        return outcome;
    }

    /**
     * Finalize a just-approved request: lift any exclusion + record the SCIM id
     * and assignment type, stamp the JIT TTL / re-request policy, and (when a
     * decider is supplied) set decidedBy. No-op when not approved. Returns an
     * audit-note suffix. Shared by the decision flow and the auto-rule path.
     */
    public String applyGrant(String requestId, boolean approved, Integer ttlMinutes,
                             Boolean reRequestable, String decider) {
        if (!approved) {
            return "";
        }
        CalloutRequest fresh = approvalsRepository.findByRequestId(requestId);
        if (fresh == null) {
            return "";
        }
        entitlementsInterface.grantAccess(fresh); // resolves + records scimUserId + assignmentType
        String note = "";
        if (ttlMinutes != null && ttlMinutes > 0) {
            fresh.setAccessTtlMinutes(ttlMinutes);
            fresh.setAccessExpiresAt(Date.from(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES)));
            boolean canReRequest = !Boolean.FALSE.equals(reRequestable);
            fresh.setReRequestable(canReRequest);
            note = " (time-bound: access expires in " + ttlMinutes + " min"
                    + (canReRequest ? ", re-requestable after" : ", permanent revoke") + ")";
        }
        if (decider != null) {
            fresh.setDecidedBy(decider);
        }
        approvalsRepository.save(fresh);
        return note;
    }
}
