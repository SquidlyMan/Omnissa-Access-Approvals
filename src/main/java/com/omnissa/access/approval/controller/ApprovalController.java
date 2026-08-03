package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.dto.PagedResponse;
import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.ApprovalChain;
import com.omnissa.access.approval.model.ApprovalStage;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.model.DecisionRequest;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.service.ApprovalChainService;
import com.omnissa.access.approval.util.Csv;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.CustomContentTypes;
import com.omnissa.access.approval.util.MailNotification;
import com.omnissa.access.approval.util.RestPreconditions;
import com.omnissa.access.approval.util.RuleEngine;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = Mappings.APPROVALS)
public class ApprovalController {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalController.class);

    @Autowired
    ApprovalsInterface approvalsInterface;

    @Autowired
    com.omnissa.access.approval.service.DecisionService decisionService;

    @Autowired
    ApprovalsRepository approvalsRepository;

    @Autowired
    MailNotification mailNotification;

    @Autowired
    SseController sseController;

    @Autowired
    AuditService auditService;

    @Autowired
    WebhookNotifier webhookNotifier;

    @Autowired
    RuleEngine ruleEngine;

    @Autowired
    ApprovalChainService approvalChainService;

    @Autowired
    com.omnissa.access.approval.service.DelegationService delegationService;

    @Autowired
    com.omnissa.access.approval.service.ApproverDirectoryService approverDirectory;

    @Autowired
    com.omnissa.access.approval.service.EscalationService escalationService;

    @Autowired
    com.omnissa.access.approval.repository.AutoRuleRepository autoRuleRepository;

    @GetMapping("/pending/remote")
    public ResponseEntity<?> getRemotePendingApprovals() {
        return ResponseEntity.ok(approvalsInterface.getPendingApprovals());
    }

    /**
     * Manual sync: pull all pending requests from Omnissa Access and ingest any
     * the local queue does not already have. Recovers requests Access holds but
     * never successfully pushed (e.g. a callout that hit a container restart or
     * a transient network gap — Access does not auto-retry the push).
     */
    @PostMapping("/pull")
    public ResponseEntity<?> pullFromAccess() {
        int pulled = 0;
        int total = 0;
        try {
            CalloutRequest[] remote = approvalsInterface.getPendingApprovals();
            if (remote != null) {
                total = remote.length;
                for (CalloutRequest req : remote) {
                    if (req == null || req.getRequestId() == null || req.getRequestId().isBlank()) {
                        continue;
                    }
                    if (approvalsRepository.findByRequestId(req.getRequestId()) != null) {
                        continue; // already have it locally
                    }
                    req.setState("pending");
                    approvalsRepository.save(req);
                    auditService.recordFor("request-received", req, "Pulled from Omnissa Access (manual sync)");
                    sseController.publishNewRequest(req);
                    pulled++;
                }
            }
            sseController.publishQueueUpdate("queue-updated");
        } catch (Exception e) {
            logger.error("Manual pull from Omnissa Access failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(java.util.Map.of("error", "Could not reach Omnissa Access: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
        return ResponseEntity.ok(java.util.Map.of("pulled", pulled, "total", total));
    }

    @GetMapping("/requests")
    public ResponseEntity<PagedResponse<CalloutRequest>> getLocalApprovals(
            @RequestParam(required = false) String state, Pageable pageable) {
        String filter = state != null ? state : "pending";
        if ("deactivated".equals(filter)) {
            // Expired (decision undeliverable) and revoked (JIT access torn down)
            // requests ride in the Deactivated tab.
            return ResponseEntity.ok(PagedResponse.from(approvalsRepository.findByStateInOrderByIdDesc(
                    List.of("deactivated", "expired", "revoked"), pageable)));
        }
        return ResponseEntity.ok(PagedResponse.from(
                approvalsRepository.findByStateOrderByIdDesc(filter, pageable)));
    }

    @GetMapping(value = "/requests/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CalloutRequest> getRequest(@PathVariable String requestId) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        RestPreconditions.checkNotNull(request, "A Callout Request with ID: " + requestId + " was not found");
        return ResponseEntity.ok(request);
    }

    /**
     * Permanently delete a request's LOCAL record from the approval tool. This
     * is an administrative cleanup for stale/orphaned entries (e.g. a request
     * Omnissa Access has already closed but the tool still shows) — it does NOT
     * call Omnissa Access or change any entitlement. Authenticated; every
     * deletion is fully audited (who, what, prior state) before the row is
     * removed. The UI gates this behind explicit multi-step confirmation.
     */
    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<?> deleteLocalRequest(@PathVariable String requestId) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }

        // Refuse while the request is still pending. Omnissa Access holds an
        // approval open until it receives a decision; deleting the local record
        // discards the only means of answering, so the requester waits forever
        // on a decision that can never be given and the app never provisions.
        // Nothing surfaces the cause — this presented as an Access provisioning
        // fault for days. Decline it first (a decline is always available), then
        // the record deletes harmlessly.
        if ("pending".equalsIgnoreCase(request.getState())) {
            logger.warn("Refused deletion of PENDING request {} by {} — Omnissa Access is still "
                    + "waiting on a decision", requestId, auditService.currentAdmin());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "This request is still pending, and Omnissa Access is waiting for a "
                            + "decision on it. Deleting it here would leave the requester waiting "
                            + "indefinitely. Decline it first, then delete the record.",
                    "state", request.getState()));
        }

        String admin = auditService.currentAdmin();
        String detail = "Local request record permanently deleted by " + admin
                + " — does NOT affect Omnissa Access. [state=" + request.getState()
                + ", operation=" + request.getOperation()
                + ", userId=" + request.getUserId()
                + ", received=" + isoDate(request.getReceivedDate())
                + ", decidedBy=" + request.getDecidedBy() + "]";
        // Audit BEFORE deletion so the record survives even if the delete races.
        auditService.recordFor("request-deleted", request, detail);
        logger.warn("LOCAL REQUEST DELETED by {}: requestId={} resourceName={} state={} operation={}",
                admin, requestId, request.getResourceName(), request.getState(), request.getOperation());
        approvalsRepository.delete(request);
        sseController.publishQueueUpdate("queue-updated");
        return ResponseEntity.ok(Map.of("deleted", true, "requestId", requestId));
    }

    /**
     * Revoke an already-granted app on demand (no TTL wait). {@code permanent=false}
     * removes access and lets the app return to a requestable state after a short
     * hold; {@code permanent=true} leaves the user excluded until an admin lifts
     * the block. Authenticated and audited.
     */
    @PostMapping("/requests/{requestId}/revoke")
    public ResponseEntity<?> revokeAccess(@PathVariable String requestId,
                                          @RequestParam(defaultValue = "false") boolean permanent) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"approved".equalsIgnoreCase(request.getState())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Only an approved request can have its access revoked (this one is "
                            + request.getState() + ")."));
        }
        var outcome = decisionService.revokeNow(request, permanent, auditService.currentAdmin());
        return ResponseEntity.ok(Map.of("outcome", outcome.name().toLowerCase()));
    }

    /**
     * Lift a permanent decline (#57): remove the user's exclusion in Omnissa
     * Access so they may request the app again. Recovery path for a decline
     * applied in error — without it, un-blocking would require direct API/console
     * surgery. Authenticated and audited.
     */
    @PostMapping("/requests/{requestId}/allow-rerequest")
    public ResponseEntity<?> allowReRequest(@PathVariable String requestId) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        if (!Boolean.FALSE.equals(request.getReRequestable())) {
            return ResponseEntity.ok(Map.of("outcome", "not_blocked",
                    "detail", "This request does not carry a permanent block."));
        }
        var outcome = decisionService.allowReRequest(request, auditService.currentAdmin());
        return ResponseEntity.ok(Map.of("outcome", outcome.name().toLowerCase()));
    }

    @PostMapping(value = "/new",
            consumes = {CustomContentTypes.APPROVAL_MESSAGE_REQUEST, CustomContentTypes.MESSAGING_MESSAGE})
    public ResponseEntity<?> saveCalloutRequest(@RequestBody(required = false) String rawBody) {
        CalloutRequest calloutRequest = parseCalloutBody(rawBody);
        // Access sends an empty test POST when the approvals settings are saved —
        // acknowledge it but don't store a junk all-null request (it blanks the UI).
        if (calloutRequest == null || calloutRequest.getRequestId() == null
                || calloutRequest.getRequestId().isBlank()) {
            logger.info("Ignoring callout probe without requestId");
            return new ResponseEntity<>(HttpStatus.OK);
        }
        logger.info("Received callout request: {}", calloutRequest);

        // Omnissa Access delivers each callout from more than one node: two POSTs
        // carrying the same requestId have been observed arriving 25ms apart from
        // different egress addresses. That is ordinary at-least-once delivery —
        // the sender guarantees arrival and leaves duplicate-suppression to the
        // receiver — so ingesting the second copy is our defect, not theirs.
        //
        // It stayed hidden while callout authentication was broken: one leg was
        // always rejected with a 401, so only one copy ever reached the database
        // and a failing handshake was accidentally deduplicating the queue. When
        // authentication started working, both legs landed, and a single
        // duplicated row then broke every lookup by requestId — the detail view,
        // the decision paths, the sweeps — because that query expects one result.
        //
        // Answering 200 is deliberate: this IS success from the sender's point of
        // view, the request is recorded. Anything else invites Access to retry
        // harder and deliver more copies.
        CalloutRequest existing = approvalsRepository.findByRequestId(calloutRequest.getRequestId());
        if (existing != null) {
            logger.info("Duplicate callout for requestId {} (already stored as id={}, state={}); "
                            + "acknowledging without storing a second copy",
                    calloutRequest.getRequestId(), existing.getId(), existing.getState());
            return new ResponseEntity<>(HttpStatus.OK);
        }

        boolean deactivation = calloutRequest.getOperation() == CalloutOperation.deactivation;
        calloutRequest.setState(deactivation ? "deactivated" : "pending");

        approvalsRepository.save(calloutRequest);
        auditService.recordFor(deactivation ? "deactivation-received" : "request-received",
                calloutRequest, "Callout received");
        sseController.publishNewRequest(calloutRequest);
        if (!deactivation) {
            webhookNotifier.notifyNewRequest(calloutRequest);
            if (!routeToChain(calloutRequest)) {
                applyAutoRules(calloutRequest);
            }
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * Routes a fresh request into a matching approval chain (#53) instead of
     * the ordinary single-decision flow, when one matches. Returns true if
     * routed — in which case auto-rules must NOT run: a chain exists
     * specifically to require sequential human judgment, so letting a MATCH
     * rule auto-decide the request on arrival would defeat the point.
     */
    private boolean routeToChain(CalloutRequest calloutRequest) {
        try {
            ApprovalChain chain = approvalChainService.matchChain(calloutRequest);
            if (chain == null) {
                return false;
            }
            calloutRequest.setChainId(chain.getId());
            calloutRequest.setCurrentStage(1);
            approvalsRepository.save(calloutRequest);
            List<ApprovalStage> stages = approvalChainService.stagesFor(chain.getId());
            logger.info("Approval chain #{} ('{}') matched requestId={} — stage 1 of {}",
                    chain.getId(), chain.getName(), calloutRequest.getRequestId(), stages.size());
            auditService.recordFor("chain-matched", calloutRequest,
                    "Routed to approval chain \"" + chain.getName() + "\" — stage 1 of " + stages.size());
            approvalChainService.notifyStageApprovers(calloutRequest, chain.getId(), stages.get(0));
            return true;
        } catch (Exception e) {
            logger.error("Chain matching failed for requestId={} — falling back to auto-rules",
                    calloutRequest.getRequestId(), e);
            return false;
        }
    }

    /**
     * Parses an inbound callout body. Access wraps the callout in a messaging
     * envelope whose "body" field is a JSON-encoded STRING of the actual request:
     * {"type":...,"body":"{\"operation\":...}"}. Unwrap it; fall back to parsing
     * the payload directly (admin-API flat format). Returns null for blank input
     * or anything that cannot be parsed.
     */
    static CalloutRequest parseCalloutBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            var root = mapper.readTree(rawBody);
            var bodyNode = root.get("body");
            String payload = (bodyNode != null && bodyNode.isTextual()) ? bodyNode.asText() : rawBody;
            return mapper.readValue(payload, CalloutRequest.class);
        } catch (Exception e) {
            logger.warn("Could not parse callout body ({}): {}", e.getMessage(), rawBody);
            return null;
        }
    }

    /**
     * Auto-approve/reject a fresh activation request when a MATCH rule fires.
     * Failures are swallowed — rule problems must never break callout ingestion.
     */
    private void applyAutoRules(CalloutRequest calloutRequest) {
        try {
            AutoRule rule = ruleEngine.evaluate(calloutRequest);
            if (rule == null) {
                return;
            }
            boolean approve = "approve".equals(rule.getAction());
            String message = (approve ? "Auto-approved" : "Auto-rejected") + " by rule #" + rule.getId();
            logger.info("Auto-rule #{} matched requestId={} — {}",
                    rule.getId(), calloutRequest.getRequestId(), rule.getAction());
            DecisionOutcome outcome = approvalsInterface.requestResponse(new CalloutResponse(
                    calloutRequest.getRequestId(), approve, message));
            switch (outcome) {
                case DELIVERED -> {
                    // Rule-driven JIT grants default to re-requestable (Option 2).
                    // decider=null → keep the decidedBy set during delivery (not a human).
                    String ttlNote = decisionService.applyGrant(calloutRequest.getRequestId(),
                            approve, rule.getGrantTtlMinutes(), null, null);
                    auditService.recordFor(approve ? "auto-approved" : "auto-rejected",
                            calloutRequest, message + ttlNote);
                    // Re-read so the notification reports the TTL applyGrant just
                    // persisted; calloutRequest predates it.
                    CalloutRequest decided = approvalsRepository.findByRequestId(calloutRequest.getRequestId());
                    webhookNotifier.notifyDecision(decided != null ? decided : calloutRequest,
                            approve, "auto-approval-rule", "#" + rule.getId());
                    sseController.publishQueueUpdate("queue-updated");
                }
                case EXPIRED -> {
                    auditService.recordFor("decision-undeliverable", calloutRequest,
                            "Decision by auto-approval-rule #" + rule.getId()
                                    + " could not be delivered — request no longer exists in Omnissa Access");
                    webhookNotifier.notifyExpired(calloutRequest);
                    sseController.publishQueueUpdate("queue-updated");
                }
                case UNREACHABLE -> logger.warn(
                        "Auto-rule #{} decision for requestId={} not delivered — Omnissa Access unreachable; request stays pending",
                        rule.getId(), calloutRequest.getRequestId());
            }
        } catch (Exception e) {
            logger.error("Auto-rule evaluation failed for requestId={}",
                    calloutRequest.getRequestId(), e);
        }
    }

    /**
     * Claim, release or assign a pending request (#51).
     *
     * <p>All three are <strong>advisory</strong>: they change who the queue
     * shows as holding a request, never who may decide it. A claimed request
     * is still decidable by any approver — see {@code DelegationService}.
     */
    @PostMapping("/requests/{requestId}/claim")
    public ResponseEntity<?> claimRequest(@PathVariable String requestId) {
        var result = delegationService.claim(requestId, auditService.currentAdmin());
        return delegationResponse(result);
    }

    @PostMapping("/requests/{requestId}/release")
    public ResponseEntity<?> releaseRequest(@PathVariable String requestId) {
        var result = delegationService.release(requestId, auditService.currentAdmin());
        return delegationResponse(result);
    }

    @PostMapping("/requests/{requestId}/assign")
    public ResponseEntity<?> assignRequest(@PathVariable String requestId,
                                           @RequestBody Map<String, String> body) {
        var result = delegationService.assign(requestId, body.get("assignee"), auditService.currentAdmin());
        return delegationResponse(result);
    }

    private ResponseEntity<?> delegationResponse(com.omnissa.access.approval.service.DelegationService.Result result) {
        if (result.ok()) {
            return ResponseEntity.ok(Map.of(
                    "outcome", "ok",
                    "message", result.message(),
                    "assignedOwner", result.request() != null && result.request().getAssignedOwner() != null
                            ? result.request().getAssignedOwner() : ""));
        }
        HttpStatus status = switch (result.outcome()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_HELD -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "outcome", result.outcome().name().toLowerCase(), "error", result.message()));
    }

    /**
     * The approver pool, for the assign picker — resolved live from Omnissa
     * Access via the groups already mapped to APPROVER/ADMIN in
     * {@code OMNISSA_ROLE_MAP}. There is deliberately no separate approver
     * list to maintain.
     */
    @GetMapping("/approvers")
    public ResponseEntity<?> listApprovers() {
        return ResponseEntity.ok(approverDirectory.escalationRecipients().stream()
                .map(m -> {
                    Map<String, String> entry = new java.util.LinkedHashMap<>();
                    entry.put("identity", m.email() != null ? m.email()
                            : m.userName() != null ? m.userName() : m.scimId());
                    entry.put("displayName", m.displayName() != null ? m.displayName() : "");
                    entry.put("email", m.email() != null ? m.email() : "");
                    return entry;
                })
                .toList());
    }

    /**
     * Escalate now (#51) — skips the remaining timer.
     *
     * <p>Not a demo convenience: escalation is otherwise unobservable until it
     * fires, so this is the only way an admin can confirm the rule is wired up
     * correctly. It advances the same stage counter the sweep uses, so the
     * timed stage never re-fires, and it is audited with the <em>admin</em> as
     * actor — the trail must never imply a timer fired when a human pressed a
     * button.
     */
    @PostMapping("/requests/{requestId}/escalate")
    public ResponseEntity<?> escalateNow(@PathVariable String requestId) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        if (request == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"pending".equalsIgnoreCase(request.getState())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Only a pending request can be escalated (this one is " + request.getState() + ")."));
        }
        var outcome = escalationService.escalateNow(request, auditService.currentAdmin());
        return ResponseEntity.ok(Map.of("outcome", outcome.name().toLowerCase()));
    }

    @PostMapping("/response")
    public ResponseEntity<?> approveCalloutRequest(@RequestBody DecisionRequest decision,
                                                   Authentication authentication) {
        logger.info("Processing approval response: requestId={} approved={} ttlMinutes={}",
                decision.getRequestId(), decision.isApproved(), decision.getTtlMinutes());
        CalloutRequest existingRequest = approvalsRepository.findByRequestId(decision.getRequestId());
        if (existingRequest != null) {
            // Only a chained request's CURRENT stage narrows who may decide —
            // this must never restrict the ordinary "any APPROVER" case.
            String reason = approvalChainService.ineligibilityReason(existingRequest, authentication);
            if (reason != null) {
                logger.warn("Refused decision on requestId={} by {} — {}",
                        decision.getRequestId(), auditService.currentAdmin(), reason);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", reason));
            }
        }
        try {
            DecisionOutcome outcome = decisionService.decide(decision.getRequestId(), decision.isApproved(),
                    decision.getMessage(), decision.getTtlMinutes(), decision.getReRequestable(),
                    auditService.currentAdmin());
            if (outcome == DecisionOutcome.UNREACHABLE) {
                logger.warn("Decision for requestId={} not delivered — Omnissa Access unreachable; request stays pending",
                        decision.getRequestId());
            }
            // HTTP 200 for every outcome — the SPA branches on the JSON body.
            return ResponseEntity.ok(Map.of("outcome", outcome.name().toLowerCase()));
        } catch (Exception e) {
            logger.error("Error processing approval response", e);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @DeleteMapping("/remote")
    public ResponseEntity<?> deleteRemoteCallouts() {
        approvalsInterface.deleteRemoteCallouts();
        return ResponseEntity.ok(null);
    }

    @PostMapping("/response/all")
    public ResponseEntity<?> respondToAllPending(@RequestParam boolean approved) {
        String admin = auditService.currentAdmin();
        String message = (approved ? "Approved by " : "Rejected by ") + admin + " (bulk action)";
        for (CalloutRequest request : approvalsRepository.findByState("pending")) {
            // A chained request (#53) requires a SPECIFIC stage's approver, not
            // "any APPROVER" — bulk-deciding it would either bypass that check
            // entirely (approving) or, if rejected, be indistinguishable from a
            // deliberate single reject. Skip it; decide it individually instead.
            if (request.getChainId() != null) {
                logger.info("Bulk action skipped requestId={} — it's on approval chain #{}, stage {}",
                        request.getRequestId(), request.getChainId(), request.getCurrentStage());
                continue;
            }
            try {
                DecisionOutcome outcome = approvalsInterface.requestResponse(
                        new CalloutResponse(request.getRequestId(), approved, "bulk action"));
                switch (outcome) {
                    case DELIVERED -> {
                        auditService.recordFor(approved ? "approved" : "rejected", request, message);
                        webhookNotifier.notifyDecision(request, approved, admin, null);
                        mailNotification.sendEmailNotification(request.getRequestId(), approved);
                    }
                    case EXPIRED -> {
                        auditService.recordFor("decision-undeliverable", request,
                                (approved ? "Approval by " : "Rejection by ") + admin
                                        + " could not be delivered — request no longer exists in Omnissa Access");
                        webhookNotifier.notifyExpired(request);
                    }
                    case UNREACHABLE -> logger.warn(
                            "Bulk decision for requestId={} not delivered — Omnissa Access unreachable; request stays pending",
                            request.getRequestId());
                }
            } catch (Exception e) {
                logger.error("Bulk action failed for requestId={}", request.getRequestId(), e);
            }
        }
        sseController.publishQueueUpdate("queue-updated");
        return ResponseEntity.ok(null);
    }

    @GetMapping("/export.csv")
    public ResponseEntity<String> exportCsv() {
        StringBuilder csv = new StringBuilder(
                "requestId,resourceName,userId,operation,state,receivedDate,responseDate,"
                + "decidedBy,accessExpiresAt,revokedAt,responseMessage\n");
        for (CalloutRequest request : approvalsRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))) {
            csv.append(csvField(request.getRequestId())).append(',')
               .append(csvField(request.getResourceName())).append(',')
               .append(csvField(request.getUserId())).append(',')
               .append(csvField(request.getOperation())).append(',')
               .append(csvField(request.getState())).append(',')
               .append(csvField(isoDate(request.getReceivedDate()))).append(',')
               .append(csvField(isoDate(request.getResponseDate()))).append(',')
               .append(csvField(request.getDecidedBy())).append(',')
               .append(csvField(isoDate(request.getAccessExpiresAt()))).append(',')
               .append(csvField(isoDate(request.getRevokedAt()))).append(',')
               .append(csvField(request.getResponseMessage())).append('\n');
        }
        String filename = "approval-requests-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(csv.toString(), headers, HttpStatus.OK);
    }

    /**
     * Finalize a just-approved request (#49). On approval we lift any per-user
     * exclusion so access applies (needed when the app is configured
     * default-excluded), capture the requester's resolved SCIM id for the later
     * revoke, and — for a time-bound grant — stamp accessTtlMinutes +
     * accessExpiresAt so the expiry sweep re-applies the exclusion. Re-fetches
     * the entity (decision delivery saved state='approved' on a separate
     * instance). No-op when not approved. Returns an audit-note suffix.
     */
    private String isoDate(Date date) {
        return date != null ? date.toInstant().toString() : null;
    }

    /** RFC-4180 escaping: quote fields containing comma/quote/newline, doubling inner quotes. */
    static String csvField(Object value) {
        return Csv.field(value);
    }
}
