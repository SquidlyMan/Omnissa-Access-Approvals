package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.EscalationOutcome;
import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.HubNotificationOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.RuleEngine;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Escalation (#51): a request nobody attends to otherwise has exactly one
 * outcome — the expiry rule auto-rejects it after N days, and the requester
 * finds out by being denied. This is the missing middle: after N minutes
 * pending, say so, to the chat channel and to the approvers themselves.
 *
 * <p>Runs on escalation's own thread pool (see {@code
 * EscalationSchedulerConfig}), so its network calls cannot stall JIT expiry.
 */
@Service
public class EscalationService {

    private static final Logger logger = LoggerFactory.getLogger(EscalationService.class);

    /**
     * Enabling a rule on an existing backlog would otherwise escalate hundreds
     * of requests in a single pass. The remainder is deferred to the next one,
     * and hitting the cap is logged rather than silently truncated.
     */
    private static final int MAX_PER_PASS = 50;

    @Autowired private ApprovalsRepository approvalsRepository;
    @Autowired private AuditService auditService;
    @Autowired private WebhookNotifier webhookNotifier;
    @Autowired private RuleEngine ruleEngine;
    @Autowired private ApproverDirectoryService approverDirectory;
    @Autowired private HubNotificationService hubNotificationService;
    @Autowired private SseController sseController;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    /**
     * Fires escalation for every pending request past {@code rule}'s
     * threshold that the rule's criteria select.
     *
     * <p>The approver pool is resolved <strong>once per pass</strong>, not per
     * request: it is identical for every request in the pass, and resolving it
     * per request would multiply SCIM traffic by the size of the backlog.
     *
     * @return how many requests were escalated
     */
    public int escalateFor(AutoRule rule) {
        Integer after = rule.getEscalateAfterMinutes();
        if (after == null || after <= 0) {
            return 0;
        }
        Date threshold = Date.from(Instant.now().minus(Duration.ofMinutes(after)));

        List<CalloutRequest> candidates = approvalsRepository.findByState("pending").stream()
                .filter(r -> r.getReceivedDate() != null && r.getReceivedDate().before(threshold))
                .filter(r -> r.getEscalationStage() == null || r.getEscalationStage() < 1)
                // Escalation honours the rule's own appPattern/groupName, using
                // the same matcher the expiry sweep uses — "no criteria" means
                // everything, which is the ordinary rule.
                .filter(r -> ruleEngine.matchesExpiryRule(rule, r))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }

        boolean capped = candidates.size() > MAX_PER_PASS;
        if (capped) {
            logger.warn("Escalation rule #{}: {} requests are due but this pass is capped at {} — "
                            + "the remainder escalate on the next pass",
                    rule.getId(), candidates.size(), MAX_PER_PASS);
            candidates = candidates.subList(0, MAX_PER_PASS);
        }

        List<GroupMember> recipients = approverDirectory.escalationRecipients();

        int escalated = 0;
        for (CalloutRequest candidate : candidates) {
            // Each request individually, as the JIT sweeps do: requestId has no
            // unique constraint, so one duplicated row must not abort the pass.
            try {
                if (escalateOne(candidate, rule, after, recipients)) {
                    escalated++;
                }
            } catch (Exception e) {
                logger.error("Escalation rule #{} failed for requestId={}",
                        rule.getId(), candidate.getRequestId(), e);
            }
        }
        if (escalated > 0) {
            sseController.publishQueueUpdate("queue-updated");
        }
        return escalated;
    }

    private boolean escalateOne(CalloutRequest stale, AutoRule rule, int afterMinutes,
                                List<GroupMember> recipients) {
        // Re-fetch: the list was read before this loop began, and a human can
        // decide a request mid-pass. Without this the channel is told "nobody
        // has acted on this" about a request approved ninety seconds ago.
        CalloutRequest request = approvalsRepository.findByRequestId(stale.getRequestId());
        if (request == null || !"pending".equalsIgnoreCase(request.getState())) {
            return false;
        }
        if (request.getEscalationStage() != null && request.getEscalationStage() >= 1) {
            return false;
        }

        int pendingMinutes = request.getReceivedDate() == null ? afterMinutes
                : (int) Duration.between(request.getReceivedDate().toInstant(), Instant.now()).toMinutes();

        // Notify FIRST, then save. A crash between the two re-sends a nudge;
        // the reverse order would record a stage that never fired and, because
        // each stage fires once by design, never fires again. A duplicate nudge
        // is bounded noise; a missed summons is the failure this exists to
        // prevent. Chosen deliberately.
        EscalationOutcome channel = webhookNotifier.notifyEscalated(request, pendingMinutes);
        int notified = notifyApprovers(request, recipients, pendingMinutes);

        if (channel == EscalationOutcome.FAILED && notified == 0) {
            // Something was configured and nothing got through — leave the
            // stage un-advanced so the next pass retries, exactly as the JIT
            // sweeps leave UNREACHABLE.
            logger.warn("Escalation for requestId={} delivered nowhere — leaving un-escalated to retry",
                    request.getRequestId());
            return false;
        }

        request.setEscalationStage(1);
        request.setEscalatedAt(new Date());
        approvalsRepository.save(request);

        auditService.recordFor("request-escalated", request, escalationNote(rule, pendingMinutes, channel, notified));
        return true;
    }

    /**
     * Escalate one request immediately, on an admin's say-so, skipping the
     * remaining timer.
     *
     * <p>Advances the same stage counter the sweep uses, so the timed stage
     * never re-fires afterwards. Audited with the admin as actor and wording
     * that says the timer had <em>not</em> elapsed — the trail must never
     * imply a timer fired when a human pressed a button.
     */
    public EscalationOutcome escalateNow(CalloutRequest request, String actor) {
        int pendingMinutes = request.getReceivedDate() == null ? 0
                : (int) Duration.between(request.getReceivedDate().toInstant(), Instant.now()).toMinutes();

        EscalationOutcome channel = webhookNotifier.notifyEscalated(request, pendingMinutes);
        int notified = notifyApprovers(request, approverDirectory.escalationRecipients(), pendingMinutes);

        request.setEscalationStage(1);
        request.setEscalatedAt(new Date());
        approvalsRepository.save(request);

        String reach = switch (channel) {
            case SENT -> "chat channel notified";
            case NOT_CONFIGURED -> "no chat channel configured";
            case FAILED -> "chat channel notification FAILED";
        };
        auditService.recordFor("request-escalated", request,
                "Escalated manually by " + actor + " before the timer elapsed — pending "
                        + WebhookNotifier.humanDuration(pendingMinutes) + "; " + reach + "; "
                        + (notified > 0 ? notified + " approver(s) notified in Omnissa Hub"
                                        : "no approvers resolved to notify"),
                actor);
        sseController.publishQueueUpdate("queue-updated");
        return channel;
    }

    /** Says what actually happened, so the trail never claims a nudge that was not sent. */
    private String escalationNote(AutoRule rule, int pendingMinutes, EscalationOutcome channel, int notified) {
        String reach = switch (channel) {
            case SENT -> "chat channel notified";
            case NOT_CONFIGURED -> "no chat channel configured";
            case FAILED -> "chat channel notification FAILED";
        };
        String people = notified > 0
                ? notified + " approver(s) notified in Omnissa Hub"
                : "no approvers resolved to notify";
        return "Escalated by rule #" + rule.getId() + " — pending "
                + WebhookNotifier.humanDuration(pendingMinutes) + "; " + reach + "; " + people;
    }

    /** @return how many approvers Access accepted a notification for */
    private int notifyApprovers(CalloutRequest request, List<GroupMember> recipients, int pendingMinutes) {
        if (recipients == null || recipients.isEmpty()) {
            return 0;
        }
        try {
            List<String> ids = recipients.stream().map(GroupMember::scimId).filter(java.util.Objects::nonNull).toList();
            if (ids.isEmpty()) {
                return 0;
            }
            String title = "Still waiting: " + safe(request.getResourceName());
            String description = safe(request.getResourceName()) + " has been awaiting a decision for "
                    + WebhookNotifier.humanDuration(pendingMinutes)
                    + " (requested by " + safe(request.getUserId()) + "). Nobody has decided it yet.";
            Map<String, HubNotificationOutcome> outcomes =
                    hubNotificationService.notifyUsers(ids, title, description, deepLink(request));
            return (int) outcomes.values().stream().filter(o -> o == HubNotificationOutcome.SENT).count();
        } catch (Exception e) {
            logger.warn("Approver notification failed for requestId={}: {}", request.getRequestId(), e.getMessage());
            return 0;
        }
    }

    private String deepLink(CalloutRequest request) {
        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            return null;
        }
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        return base + "/requests/" + request.getRequestId();
    }

    /**
     * Releases claims that have gone stale, so an abandoned claim cannot sit
     * there reading as "handled" to everyone else.
     *
     * @return how many were released
     */
    public int releaseStaleClaims(AutoRule rule) {
        Integer ttl = rule.effectiveClaimTtlMinutes();
        if (ttl == null || ttl <= 0) {
            return 0;
        }
        Date threshold = Date.from(Instant.now().minus(Duration.ofMinutes(ttl)));
        int released = 0;
        for (CalloutRequest stale : approvalsRepository.findByState("pending")) {
            if (stale.getAssignedOwner() == null || stale.getAssignedAt() == null
                    || !stale.getAssignedAt().before(threshold)) {
                continue;
            }
            try {
                CalloutRequest request = approvalsRepository.findByRequestId(stale.getRequestId());
                if (request == null || !"pending".equalsIgnoreCase(request.getState())
                        || request.getAssignedOwner() == null || request.getAssignedAt() == null) {
                    continue;
                }
                long heldMinutes = Duration.between(request.getAssignedAt().toInstant(), Instant.now()).toMinutes();
                String owner = request.getAssignedOwner();
                request.setAssignedOwner(null);
                request.setAssignedAt(null);
                approvalsRepository.save(request);
                // Names the owner and how long they held it, or a handover
                // chain cannot be reconstructed from the trail. Actor is
                // "system", which is what distinguishes a lapse from a manual
                // release.
                auditService.recordFor("request-released", request,
                        "Claim by " + owner + " released automatically after "
                                + WebhookNotifier.humanDuration((int) heldMinutes)
                                + " without a decision (TTL " + ttl + " min)", "system");
                released++;
            } catch (Exception e) {
                logger.error("Claim TTL release failed for requestId={}", stale.getRequestId(), e);
            }
        }
        if (released > 0) {
            sseController.publishQueueUpdate("queue-updated");
        }
        return released;
    }

    /**
     * Count of pending requests past their threshold whose escalation has not
     * caught up. Zero in steady state; a persistently non-zero value is the
     * single signal that says "escalation is running and accomplishing
     * nothing" — covering webhook misconfiguration, matcher bugs and sweep
     * stalls in one number.
     */
    public long overdueCount(List<AutoRule> escalationRules) {
        long overdue = 0;
        for (AutoRule rule : escalationRules) {
            Integer after = rule.getEscalateAfterMinutes();
            if (after == null || after <= 0) {
                continue;
            }
            Date threshold = Date.from(Instant.now().minus(Duration.ofMinutes(after)));
            overdue += approvalsRepository.findByState("pending").stream()
                    .filter(r -> r.getReceivedDate() != null && r.getReceivedDate().before(threshold))
                    .filter(r -> r.getEscalationStage() == null || r.getEscalationStage() < 1)
                    .filter(r -> ruleEngine.matchesExpiryRule(rule, r))
                    .count();
        }
        return overdue;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
