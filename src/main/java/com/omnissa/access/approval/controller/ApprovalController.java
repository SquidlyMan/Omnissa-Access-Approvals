package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.CustomContentTypes;
import com.omnissa.access.approval.util.MailNotification;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        logger.info("Raw callout body: {}", rawBody);
        CalloutRequest calloutRequest = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                calloutRequest = new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(rawBody, CalloutRequest.class);
            } catch (Exception e) {
                logger.warn("Could not parse callout body: {}", e.getMessage());
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

        calloutRequest.setState(calloutRequest.getOperation() == CalloutOperation.deactivation
                ? "deactivated" : "pending");

        approvalsRepository.save(calloutRequest);
        sseController.publishNewRequest(calloutRequest);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/response")
    public ResponseEntity<?> approveCalloutRequest(@RequestBody CalloutResponse calloutResponse) {
        logger.info("Processing approval response: {}", calloutResponse);
        try {
            approvalsInterface.requestResponse(calloutResponse);
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
                mailNotification.sendEmailNotification(request.getRequestId(), approved);
            } catch (Exception e) {
                logger.error("Bulk action failed for requestId={}", request.getRequestId(), e);
            }
        }
        sseController.publishQueueUpdate("queue-updated");
        return ResponseEntity.ok(null);
    }
}
