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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** When true and format=slack, post an interactive (Approve/Reject + duration) message. */
    @Value("${slack.actionable:false}")
    private boolean slackActionable;

    /** JIT duration menu offered in the Slack message. value = minutes ("0" = permanent). */
    private static final String[][] SLACK_TTL_OPTIONS = {
            {"Permanent", "0"}, {"5 minutes", "5"}, {"15 minutes", "15"}, {"1 hour", "60"},
            {"8 hours", "480"}, {"24 hours", "1440"}, {"7 days", "10080"}, {"30 days", "43200"}
    };

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
        Object payload = ("slack".equals(resolvedFormat()) && slackActionable)
                ? buildSlackActionableMessage(request)
                : buildNewRequestPayload(request);
        postAsync(payload, request.getRequestId());
    }

    /**
     * Interactive Slack message (#50): request summary, a JIT duration menu, and
     * Approve/Reject buttons. Button {@code value} carries the requestId; the
     * duration select's current value is read from the interaction's
     * {@code state} when a button is clicked (see SlackController).
     *
     * <p>Built from plain Map/List structures on purpose: Spring Boot 4 converts
     * request bodies with Jackson 3, which does not recognize a Jackson 2
     * {@code ObjectNode} as a tree and serializes it as an opaque POJO — Slack
     * then rejects the post with {@code 400 "no_text"} and the message is lost.
     */
    Map<String, Object> buildSlackActionableMessage(CalloutRequest request) {
        String requester = requesterLabel(request);
        Map<String, Object> root = new LinkedHashMap<>();
        // Fallback text — also what notifications//unfurls show.
        root.put("text", "New access request: " + request.getResourceName() + " (" + requester + ")");

        List<Object> blocks = new ArrayList<>();
        blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn",
                        "text", "*New access request*\n*App:* " + request.getResourceName()
                                + "\n*Requested by:* " + requester)));

        List<Object> options = new ArrayList<>();
        for (String[] opt : SLACK_TTL_OPTIONS) {
            options.add(Map.of(
                    "text", Map.of("type", "plain_text", "text", opt[0]),
                    "value", opt[1]));
        }
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("type", "static_select");
        select.put("action_id", "duration");
        select.put("placeholder", Map.of("type", "plain_text", "text", "Access duration"));
        select.put("options", options);
        // initial_option must exactly match one of the options (Permanent).
        select.put("initial_option", options.get(0));
        blocks.add(Map.of("type", "actions", "block_id", "jit_duration",
                "elements", List.of(select)));

        blocks.add(Map.of("type", "actions", "block_id", "decision", "elements", List.of(
                Map.of("type", "button", "action_id", "approve", "style", "primary",
                        "text", Map.of("type", "plain_text", "text", "✓ Approve"),
                        "value", request.getRequestId()),
                Map.of("type", "button", "action_id", "reject", "style", "danger",
                        "text", Map.of("type", "plain_text", "text", "✗ Reject"),
                        "value", request.getRequestId()))));

        root.put("blocks", blocks);
        return root;
    }

    /** Human label for the requester: prefer real name/email from callout attributes, else userId. */
    private static String requesterLabel(CalloutRequest request) {
        var attrs = request.getUserAttributes();
        if (attrs != null) {
            String first = firstAttr(attrs.get("firstName"));
            String last = firstAttr(attrs.get("lastName"));
            String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            if (!name.isBlank()) return name;
            String email = firstAttr(attrs.get("email"));
            if (email != null) return email;
        }
        return "user " + request.getUserId();
    }

    private static String firstAttr(java.util.List<String> vals) {
        return (vals != null && !vals.isEmpty() && vals.get(0) != null && !vals.get(0).isBlank()) ? vals.get(0) : null;
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

    private void postAsync(Object payload, String requestId) {
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
