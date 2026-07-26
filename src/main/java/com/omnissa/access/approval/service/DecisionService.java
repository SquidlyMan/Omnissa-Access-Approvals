package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.interfaces.EntitlementsInterface;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.model.RevokeOutcome;
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
                String ttlNote = approved
                        ? applyGrant(requestId, true, ttlMinutes, reRequestable, decider)
                        : applyDecline(requestId, reRequestable, decider);
                String note = (message != null && !message.isBlank()) ? " — " + message : "";
                // Attribute the audit entry to the resolved decider — a Slack
                // approver has no Spring session, so currentAdmin() would say "system".
                auditService.record(approved ? "approved" : "rejected", requestId,
                        request != null ? request.getResourceName() : null,
                        (approved ? "Approved by " : "Rejected by ") + decider + note + ttlNote,
                        decider);
                // Re-read: applyGrant/applyDecline persist the TTL and re-request
                // policy on their own instance, so `request` — loaded before the
                // decision was applied — still has none of it. The notification
                // states the consequence, so it must reflect what was actually
                // saved rather than what was asked for.
                CalloutRequest decided = approvalsRepository.findByRequestId(requestId);
                webhookNotifier.notifyDecision(decided != null ? decided : request,
                        approved, decider, null);
                mailNotification.sendEmailNotification(requestId, approved);
                sseController.publishQueueUpdate("queue-updated");
            }
            case EXPIRED -> {
                auditService.record("decision-undeliverable", requestId,
                        request != null ? request.getResourceName() : null,
                        (approved ? "Approval by " : "Rejection by ") + decider
                                + " could not be delivered — request no longer exists in Omnissa Access",
                        decider);
                webhookNotifier.notifyExpired(request);
                sseController.publishQueueUpdate("queue-updated");
            }
            case UNREACHABLE -> { /* leave pending; caller logs/retries */ }
        }
        return outcome;
    }

    /**
     * Finalize a rejection (#57). A <b>temporary</b> decline (the default) only
     * records the outcome — the user may request the app again. A
     * <b>permanent</b> decline additionally excludes the user in Access so the
     * app does not reappear for them: a directly-assigned user is flipped to
     * excluded, a group-assigned user gains an exclusion, leaving the group
     * entitlement untouched. Returns an audit-note suffix.
     */
    public String applyDecline(String requestId, Boolean reRequestable, String decider) {
        CalloutRequest fresh = approvalsRepository.findByRequestId(requestId);
        if (fresh == null) {
            return "";
        }
        boolean permanent = Boolean.FALSE.equals(reRequestable);
        fresh.setReRequestable(!permanent);
        if (decider != null) {
            fresh.setDecidedBy(decider);
        }
        String note = permanent ? " (permanent: user blocked from re-requesting)" : "";
        if (permanent) {
            // revokeAccess resolves + records scimUserId/assignmentType on the entity,
            // so the block can be lifted later without re-deriving identity.
            RevokeOutcome outcome = entitlementsInterface.revokeAccess(fresh);
            if (outcome == RevokeOutcome.REVOKED || outcome == RevokeOutcome.ALREADY_ABSENT) {
                fresh.setRevokedAt(new Date());
                auditService.record("access-blocked", requestId, fresh.getResourceName(),
                        "Permanently declined by " + decider
                                + " — user excluded from the app in Omnissa Access; cannot re-request", decider);
            } else {
                // Don't claim a block that did not happen.
                note = " (permanent decline requested, but the exclusion could NOT be applied — "
                        + outcome + "; the user can still re-request)";
                fresh.setReRequestable(true);
                auditService.record("access-block-failed", requestId, fresh.getResourceName(),
                        "Permanent decline by " + decider + " could not be enforced (" + outcome
                                + ") — no exclusion applied", decider);
            }
        }
        approvalsRepository.save(fresh);
        return note;
    }

    /** Hold before lifting the exclusion, so Access finishes deprovisioning first. */
    private static final long REVOKE_RESTORE_HOLD_MINUTES = 1;

    /**
     * Revoke an already-granted app on demand, without waiting for a TTL.
     *
     * <p>Both modes exclude the user in Access (a directly-assigned user is
     * flipped to excluded; a group-assigned user gains an exclusion), which is
     * what actually deprovisions the running app.
     *
     * @param permanent {@code false} = the exclusion is lifted after a short hold
     *                  so the app returns to a requestable state;
     *                  {@code true} = the exclusion stays and the app does not
     *                  reappear until an admin lifts it.
     */
    public RevokeOutcome revokeNow(CalloutRequest request, boolean permanent, String actor) {
        RevokeOutcome outcome = entitlementsInterface.revokeAccess(request);
        if (outcome != RevokeOutcome.REVOKED && outcome != RevokeOutcome.ALREADY_ABSENT) {
            return outcome; // nothing changed — don't record a revocation that didn't happen
        }
        Date now = new Date();
        request.setState("revoked");
        request.setRevokedAt(now);
        request.setReRequestable(!permanent);
        if (!permanent) {
            request.setRestoreAt(Date.from(now.toInstant().plusSeconds(REVOKE_RESTORE_HOLD_MINUTES * 60)));
        } else {
            request.setRestoreAt(null);
        }
        approvalsRepository.save(request);
        auditService.record("access-revoked", request.getRequestId(), request.getResourceName(),
                "Access revoked by " + actor + " — user excluded in Omnissa Access"
                        + (permanent
                            ? "; permanent — the app will not reappear until an admin lifts the block"
                            : "; the app will return to a requestable state shortly"), actor);
        webhookNotifier.notifyRevoked(request);
        sseController.publishQueueUpdate("queue-updated");
        return outcome;
    }

    /**
     * Lift a permanent decline (#57): remove the exclusion so the user may
     * request the app again. Returns the outcome so the caller can report it.
     */
    public RevokeOutcome allowReRequest(CalloutRequest request, String actor) {
        RevokeOutcome outcome = entitlementsInterface.restoreAccess(request);
        if (outcome == RevokeOutcome.REVOKED) {
            request.setReRequestable(true);
            request.setRestoredAt(new Date());
            approvalsRepository.save(request);
            auditService.record("access-reopened", request.getRequestId(), request.getResourceName(),
                    "Permanent decline lifted by " + actor + " — the user may request this app again", actor);
            webhookNotifier.notifyReopened(request);
            sseController.publishQueueUpdate("queue-updated");
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
