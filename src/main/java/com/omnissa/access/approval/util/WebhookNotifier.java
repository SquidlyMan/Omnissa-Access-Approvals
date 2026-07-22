package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Optional webhook notification when a new activation request arrives, a
 * request is decided (approved/rejected, manually or by an auto-rule), or a
 * request expires because a decision could not be delivered.
 * Fire-and-forget: runs async, never throws, and is a no-op when
 * webhook.url is blank. Formats: generic (JSON event), slack, teams.
 */
@Service
public class WebhookNotifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookNotifier.class);

    @Value("${webhook.url:}")
    private String webhookUrl;

    @Value("${webhook.format:generic}")
    private String webhookFormat;

    private final RestTemplate restTemplate;

    public WebhookNotifier() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void notifyNewRequest(CalloutRequest request) {
        if (request == null || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        postAsync(buildNewRequestPayload(request), request.getRequestId());
    }

    /**
     * Notifies the webhook of an approval/rejection decision.
     *
     * @param decidedBy admin username for human decisions, or the literal
     *                  "auto-approval-rule" for rule decisions
     * @param ruleLabel "#&lt;id&gt;" for rule decisions, null for human decisions
     */
    public void notifyDecision(CalloutRequest request, boolean approved, String decidedBy, String ruleLabel) {
        if (request == null || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        postAsync(buildDecisionPayload(request, approved, decidedBy, ruleLabel), request.getRequestId());
    }

    /**
     * Notifies the webhook that a request expired: a decision could not be
     * delivered because the request no longer exists in Omnissa Access.
     */
    public void notifyExpired(CalloutRequest request) {
        if (request == null || webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        postAsync(buildExpiredPayload(request), request.getRequestId());
    }

    private void postAsync(Map<String, Object> payload, String requestId) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                restTemplate.postForEntity(webhookUrl, new HttpEntity<>(payload, headers), String.class);
                logger.info("Webhook notification sent for requestId={}", requestId);
            } catch (Exception e) {
                logger.warn("Webhook notification failed for requestId={}: {}", requestId, e.getMessage());
            }
        });
    }

    private String resolvedFormat() {
        return webhookFormat == null ? "generic" : webhookFormat.trim().toLowerCase();
    }

    Map<String, Object> buildNewRequestPayload(CalloutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = resolvedFormat();
        if ("slack".equals(format) || "teams".equals(format)) {
            // Teams incoming webhooks accept the same simple text payload as Slack.
            payload.put("text", "New access request: " + request.getResourceName()
                    + " requested by user " + request.getUserId()
                    + " — approve or reject in the Access Approval Tool.");
        } else {
            payload.put("event", "request.created");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("operation", "activation");
            payload.put("receivedDate", request.getReceivedDate() != null
                    ? request.getReceivedDate().toInstant().toString()
                    : Instant.now().toString());
        }
        return payload;
    }

    Map<String, Object> buildDecisionPayload(CalloutRequest request, boolean approved,
                                                     String decidedBy, String ruleLabel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = resolvedFormat();
        if ("slack".equals(format) || "teams".equals(format)) {
            String attribution = ruleLabel != null
                    ? (approved ? "Auto-Approved by rule " : "Auto-Rejected by rule ") + ruleLabel
                    : (approved ? "Approved by " : "Rejected by ") + decidedBy;
            payload.put("text", attribution + ": " + request.getResourceName()
                    + " (user " + request.getUserId() + ")");
        } else {
            payload.put("event", "request.decided");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("decision", approved ? "approved" : "rejected");
            payload.put("decidedBy", decidedBy);
            if (ruleLabel != null) {
                payload.put("rule", ruleLabel);
            }
            payload.put("decidedDate", Instant.now().toString());
        }
        return payload;
    }

    Map<String, Object> buildExpiredPayload(CalloutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = resolvedFormat();
        if ("slack".equals(format) || "teams".equals(format)) {
            payload.put("text", "Decision could not be delivered — request no longer exists in Access: "
                    + request.getResourceName() + " (user " + request.getUserId() + ")");
        } else {
            payload.put("event", "request.expired");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("detail", "decision could not be delivered — request no longer exists in Omnissa Access");
        }
        return payload;
    }
}
