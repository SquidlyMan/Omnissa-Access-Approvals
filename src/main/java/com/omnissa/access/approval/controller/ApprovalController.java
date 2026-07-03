package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.Mappings;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.Date;

@RestController
@RequestMapping(value = Mappings.APPROVALS)
public class ApprovalController {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalController.class);

    @Autowired
    ApprovalsInterface approvalsInterface;

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

    @GetMapping("/pending/remote")
    public ResponseEntity<?> getRemotePendingApprovals() {
        return ResponseEntity.ok(approvalsInterface.getPendingApprovals());
    }

    @GetMapping("/requests")
    public ResponseEntity<Page<CalloutRequest>> getLocalApprovals(
            @RequestParam(required = false) String state, Pageable pageable) {
        String filter = state != null ? state : "pending";
        return ResponseEntity.ok(approvalsRepository.findByStateOrderByIdDesc(filter, pageable));
    }

    @GetMapping(value = "/requests/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CalloutRequest> getRequest(@PathVariable String requestId) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        RestPreconditions.checkNotNull(request, "A Callout Request with ID: " + requestId + " was not found");
        return ResponseEntity.ok(request);
    }

    @PostMapping(value = "/new",
            consumes = {CustomContentTypes.APPROVAL_MESSAGE_REQUEST, CustomContentTypes.MESSAGING_MESSAGE})
    public ResponseEntity<?> saveCalloutRequest(@RequestBody(required = false) String rawBody) {
        // Access wraps the callout in a messaging envelope whose "body" field is a
        // JSON-encoded STRING of the actual request: {"type":...,"body":"{\"operation\":...}"}.
        // Unwrap it; fall back to parsing the payload directly (admin-API flat format).
        CalloutRequest calloutRequest = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                var root = mapper.readTree(rawBody);
                var bodyNode = root.get("body");
                String payload = (bodyNode != null && bodyNode.isTextual()) ? bodyNode.asText() : rawBody;
                calloutRequest = mapper.readValue(payload, CalloutRequest.class);
            } catch (Exception e) {
                logger.warn("Could not parse callout body ({}): {}", e.getMessage(), rawBody);
            }
        }
        // Access sends an empty test POST when the approvals settings are saved —
        // acknowledge it but don't store a junk all-null request (it blanks the UI).
        if (calloutRequest == null || calloutRequest.getRequestId() == null
                || calloutRequest.getRequestId().isBlank()) {
            logger.info("Ignoring callout probe without requestId");
            return new ResponseEntity<>(HttpStatus.OK);
        }
        logger.info("Received callout request: {}", calloutRequest);

        boolean deactivation = calloutRequest.getOperation() == CalloutOperation.deactivation;
        calloutRequest.setState(deactivation ? "deactivated" : "pending");

        approvalsRepository.save(calloutRequest);
        auditService.record(deactivation ? "deactivation-received" : "request-received",
                calloutRequest.getRequestId(), calloutRequest.getResourceName(),
                "Callout received for user " + calloutRequest.getUserId());
        sseController.publishNewRequest(calloutRequest);
        if (!deactivation) {
            webhookNotifier.notifyNewRequest(calloutRequest);
            applyAutoRules(calloutRequest);
        }
        return new ResponseEntity<>(HttpStatus.OK);
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
            approvalsInterface.requestResponse(new CalloutResponse(
                    calloutRequest.getRequestId(), approve, message));
            auditService.record(approve ? "auto-approved" : "auto-rejected",
                    calloutRequest.getRequestId(), calloutRequest.getResourceName(), message);
            sseController.publishQueueUpdate("queue-updated");
        } catch (Exception e) {
            logger.error("Auto-rule evaluation failed for requestId={}",
                    calloutRequest.getRequestId(), e);
        }
    }

    @PostMapping("/response")
    public ResponseEntity<?> approveCalloutRequest(@RequestBody CalloutResponse calloutResponse) {
        logger.info("Processing approval response: {}", calloutResponse);
        try {
            CalloutRequest request = approvalsRepository.findByRequestId(calloutResponse.getRequestId());
            approvalsInterface.requestResponse(calloutResponse);
            auditService.record(calloutResponse.isApproved() ? "approved" : "rejected",
                    calloutResponse.getRequestId(),
                    request != null ? request.getResourceName() : null,
                    calloutResponse.getMessage());
            mailNotification.sendEmailNotification(calloutResponse.getRequestId(), calloutResponse.isApproved());
            sseController.publishQueueUpdate("queue-updated");
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
        for (CalloutRequest request : approvalsRepository.findByState("pending")) {
            try {
                approvalsInterface.requestResponse(
                        new CalloutResponse(request.getRequestId(), approved, "bulk action"));
                auditService.record(approved ? "approved" : "rejected",
                        request.getRequestId(), request.getResourceName(), "bulk action");
                mailNotification.sendEmailNotification(request.getRequestId(), approved);
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
                "requestId,resourceName,userId,operation,state,receivedDate,responseDate,decidedBy,responseMessage\n");
        for (CalloutRequest request : approvalsRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))) {
            csv.append(csvField(request.getRequestId())).append(',')
               .append(csvField(request.getResourceName())).append(',')
               .append(csvField(request.getUserId())).append(',')
               .append(csvField(request.getOperation())).append(',')
               .append(csvField(request.getState())).append(',')
               .append(csvField(isoDate(request.getReceivedDate()))).append(',')
               .append(csvField(isoDate(request.getResponseDate()))).append(',')
               .append(csvField(request.getDecidedBy())).append(',')
               .append(csvField(request.getResponseMessage())).append('\n');
        }
        String filename = "approval-requests-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(csv.toString(), headers, HttpStatus.OK);
    }

    private String isoDate(Date date) {
        return date != null ? date.toInstant().toString() : null;
    }

    /** RFC-4180 escaping: quote fields containing comma/quote/newline, doubling inner quotes. */
    private String csvField(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
