package com.omnissa.access.approval.config;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.interfaces.EntitlementsInterface;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.model.RevokeOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.repository.AutoRuleRepository;
import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Hourly evaluation of EXPIRY auto-rules: pending requests older than a
 * rule's expiryDays are automatically rejected.
 */
@Component
public class RuleScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RuleScheduler.class);

    @Autowired
    private AutoRuleRepository autoRuleRepository;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    @Autowired
    private ApprovalsInterface approvalsInterface;

    @Autowired
    private EntitlementsInterface entitlementsInterface;

    @Autowired
    private AuditService auditService;

    @Autowired
    private WebhookNotifier webhookNotifier;

    @Autowired
    private SseController sseController;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void applyExpiryRules() {
        List<AutoRule> expiryRules = autoRuleRepository.findAll().stream()
                .filter(rule -> rule.isEnabled()
                        && rule.getExpiryDays() != null && rule.getExpiryDays() > 0
                        && "reject".equals(rule.getAction()))
                .toList();
        if (expiryRules.isEmpty()) {
            return;
        }
        for (AutoRule rule : expiryRules) {
            Date threshold = Date.from(Instant.now().minus(rule.getExpiryDays(), ChronoUnit.DAYS));
            for (CalloutRequest request : approvalsRepository.findByState("pending")) {
                if (request.getReceivedDate() == null || !request.getReceivedDate().before(threshold)) {
                    continue;
                }
                try {
                    logger.info("Expiry rule #{} rejecting stale pending requestId={}",
                            rule.getId(), request.getRequestId());
                    DecisionOutcome outcome = approvalsInterface.requestResponse(new CalloutResponse(
                            request.getRequestId(), false,
                            "Auto-rejected: pending longer than " + rule.getExpiryDays() + " days"));
                    switch (outcome) {
                        case DELIVERED -> {
                            auditService.record("auto-rejected", request.getRequestId(), request.getResourceName(),
                                    "Auto-rejected by rule #" + rule.getId()
                                            + " (pending longer than " + rule.getExpiryDays() + " days)");
                            webhookNotifier.notifyDecision(request, false, "auto-approval-rule", "#" + rule.getId());
                        }
                        case EXPIRED -> {
                            auditService.record("decision-undeliverable",
                                    request.getRequestId(), request.getResourceName(),
                                    "Decision by auto-approval-rule #" + rule.getId()
                                            + " could not be delivered — request no longer exists in Omnissa Access");
                            webhookNotifier.notifyExpired(request);
                        }
                        case UNREACHABLE -> logger.warn(
                                "Expiry rule #{} decision for requestId={} not delivered — Omnissa Access unreachable; will retry next run",
                                rule.getId(), request.getRequestId());
                    }
                } catch (Exception e) {
                    logger.error("Expiry rule #{} failed for requestId={}",
                            rule.getId(), request.getRequestId(), e);
                }
            }
        }
    }

    /** Hold between excluding a re-requestable grant and lifting the exclusion, so
     *  Access finishes deprovisioning the app before it is re-opened for request. */
    private static final long RESTORE_HOLD_MINUTES = 1;

    /**
     * JIT / time-bound access expiry (#49). Runs every minute so short TTLs are
     * honored promptly. Finds approved grants whose TTL has elapsed and excludes
     * the user in Access. For re-requestable grants (Option 2) it also schedules
     * the exclusion to be lifted after a short hold (see {@link #applyJitRestore}).
     * UNREACHABLE/ERROR leave the request 'approved' so the next sweep retries.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void applyJitExpiry() {
        List<CalloutRequest> expired =
                approvalsRepository.findByStateAndAccessExpiresAtBefore("approved", new Date());
        if (expired.isEmpty()) {
            return;
        }
        boolean anyRevoked = false;
        for (CalloutRequest request : expired) {
            try {
                RevokeOutcome outcome = entitlementsInterface.revokeAccess(request);
                switch (outcome) {
                    case REVOKED, ALREADY_ABSENT -> {
                        // Re-fetch: revokeAccess may have run against a detached entity.
                        CalloutRequest fresh = approvalsRepository.findByRequestId(request.getRequestId());
                        if (fresh == null) {
                            break;
                        }
                        Date now = new Date();
                        fresh.setState("revoked");
                        fresh.setRevokedAt(now);
                        boolean reRequestable = Boolean.TRUE.equals(fresh.getReRequestable());
                        if (reRequestable) {
                            fresh.setRestoreAt(Date.from(now.toInstant().plusSeconds(RESTORE_HOLD_MINUTES * 60)));
                        }
                        approvalsRepository.save(fresh);
                        String detail = (outcome == RevokeOutcome.REVOKED
                                ? "JIT access expired — user excluded from the app in Omnissa Access"
                                : "JIT access expired — user was already excluded in Omnissa Access")
                                + (request.getAccessTtlMinutes() != null ? " (TTL " + request.getAccessTtlMinutes() + " min)" : "")
                                + (reRequestable ? "; app will re-open for request shortly" : "; permanent (not re-requestable)");
                        auditService.record("access-revoked", request.getRequestId(),
                                request.getResourceName(), detail);
                        anyRevoked = true;
                    }
                    case UNREACHABLE -> logger.warn(
                            "JIT revoke for requestId={} not delivered — Omnissa Access unreachable; will retry next sweep",
                            request.getRequestId());
                    case ERROR -> logger.warn(
                            "JIT revoke for requestId={} failed — will retry next sweep",
                            request.getRequestId());
                }
            } catch (Exception e) {
                logger.error("JIT expiry failed for requestId={}", request.getRequestId(), e);
            }
        }
        if (anyRevoked) {
            sseController.publishQueueUpdate("queue-updated");
        }
    }

    /**
     * JIT re-request restore (#49, Option 2). Lifts the exclusion on revoked,
     * re-requestable grants once their hold has elapsed, returning the app to a
     * requestable state. Clears {@code restoreAt} and stamps {@code restoredAt}
     * so it runs once; UNREACHABLE/ERROR leave it to retry next minute.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void applyJitRestore() {
        List<CalloutRequest> due =
                approvalsRepository.findByStateAndRestoreAtBefore("revoked", new Date());
        if (due.isEmpty()) {
            return;
        }
        boolean anyRestored = false;
        for (CalloutRequest request : due) {
            try {
                RevokeOutcome outcome = entitlementsInterface.restoreAccess(request);
                if (outcome == RevokeOutcome.REVOKED) {
                    CalloutRequest fresh = approvalsRepository.findByRequestId(request.getRequestId());
                    if (fresh == null) {
                        continue;
                    }
                    fresh.setRestoreAt(null);
                    fresh.setRestoredAt(new Date());
                    approvalsRepository.save(fresh);
                    auditService.record("access-reopened", request.getRequestId(), request.getResourceName(),
                            "JIT hold elapsed — exclusion lifted; app is requestable again ("
                            + ("USER".equals(request.getAssignmentType()) ? "re-provisioned direct user" : "group entitlement reapplies") + ")");
                    anyRestored = true;
                } else {
                    logger.warn("JIT restore for requestId={} not completed ({}); will retry next sweep",
                            request.getRequestId(), outcome);
                }
            } catch (Exception e) {
                logger.error("JIT restore failed for requestId={}", request.getRequestId(), e);
            }
        }
        if (anyRestored) {
            sseController.publishQueueUpdate("queue-updated");
        }
    }
}
