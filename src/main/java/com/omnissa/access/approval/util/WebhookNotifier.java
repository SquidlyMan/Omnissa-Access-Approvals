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

import java.net.URI;
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

    /**
     * When true, also notify on JIT lifecycle events (#49): access revoked at
     * expiry, and the app re-opened for request. Off leaves those to the audit
     * trail and the web UI only.
     */
    @Value("${webhook.notify-lifecycle:true}")
    private boolean notifyLifecycle;

    /** When true and format=teams, post an Adaptive Card with review/decision buttons (#55). */
    @Value("${teams.actionable:false}")
    private boolean teamsActionable;

    /**
     * Public base URL of this tool (e.g. https://approvals.example.com), used to
     * build deep links in Teams cards. Required for actionable Teams cards: the
     * notification is sent from a background thread with no HTTP request, so the
     * public URL cannot be derived from forwarded headers.
     */
    @Value("${app.base-url:}")
    private String appBaseUrl;


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
        String format = resolvedFormat();
        Object payload;
        if ("slack".equals(format) && slackActionable && !appBaseUrl.isBlank()) {
            payload = buildSlackActionableMessage(request);
        } else if ("teams".equals(format) && teamsActionable && !appBaseUrl.isBlank()) {
            payload = buildTeamsActionableCard(request);
        } else {
            payload = buildNewRequestPayload(request);
        }
        postAsync(payload, request.getRequestId());
    }

    /**
     * Adaptive Card for Microsoft Teams (#55) with deep-link buttons into this
     * tool.
     *
     * <p>Buttons are {@code Action.OpenUrl}, not a callback: Office 365
     * connectors (which supported {@code Action.Http}) are retired, and having a
     * Power Automate flow call back would require the premium HTTP connector.
     * Opening the tool instead keeps the flow to a single "post the payload"
     * step, adds no inbound endpoint or shared secret, and authenticates the
     * approver with the tool's own OIDC login — stronger than trusting whoever
     * can see the message.
     *
     * <p>Wrapped in the {@code type: message / attachments} envelope that a
     * Power Automate "webhook → post card" workflow expects.
     */
    Map<String, Object> buildTeamsActionableCard(CalloutRequest request) {
        String requester = requesterLabel(request);
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        String link = base + "/requests/" + request.getRequestId();

        List<Object> body = List.of(
                Map.of("type", "TextBlock", "text", "New access request",
                        "weight", "Bolder", "size", "Medium", "wrap", true),
                Map.of("type", "FactSet", "facts", List.of(
                        Map.of("title", "App", "value", String.valueOf(request.getResourceName())),
                        Map.of("title", "Requested by", "value", requester))),
                Map.of("type", "TextBlock", "wrap", true, "isSubtle", true, "spacing", "Small",
                        "text", "Choose an action to review it in the Access Approval Tool. "
                                + "You will be asked to sign in."));

        List<Object> actions = List.of(
                Map.of("type", "Action.OpenUrl", "title", "✓ Approve…",
                        "url", link + "?action=approve"),
                Map.of("type", "Action.OpenUrl", "title", "✗ Reject…",
                        "url", link + "?action=reject"),
                Map.of("type", "Action.OpenUrl", "title", "Open request", "url", link));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("version", "1.4");
        card.put("body", body);
        card.put("actions", actions);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "message");
        envelope.put("attachments", List.of(Map.of(
                "contentType", "application/vnd.microsoft.card.adaptive",
                "content", card)));
        return envelope;
    }

    /**
     * Slack message (#50) with deep-link buttons into this tool.
     *
     * <p>Buttons are URL buttons, not interactive callbacks. A callback is
     * dispatched to an endpoint where no signed-in user exists — the Slack
     * signature proves the workspace, not the person — so authorization had to
     * come from a separate approver map, a second source of truth that failed
     * <em>open</em>: removing someone from an approver group in Omnissa Access
     * revoked their web access but left their Slack buttons working. Opening
     * the tool instead means the approver signs in and the ordinary role rules
     * apply, so the two can no longer disagree.
     *
     * <p>It also removes the inbound endpoint, its signing secret and its
     * replay window, and makes a stale card harmless: the request page reads
     * live state, so a button clicked after the request was decided simply
     * shows the current outcome instead of attempting the decision.
     *
     * <p>The duration menu moves to the review dialog for the same reason a
     * Teams card has none — a URL cannot carry the state of a Slack select.
     *
     * <p>Built from plain Map/List structures on purpose: Spring Boot 4 converts
     * request bodies with Jackson 3, which does not recognize a Jackson 2
     * {@code ObjectNode} as a tree and serializes it as an opaque POJO — Slack
     * then rejects the post with {@code 400 "no_text"} and the message is lost.
     */
    Map<String, Object> buildSlackActionableMessage(CalloutRequest request) {
        String requester = requesterLabel(request);
        String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        String link = base + "/requests/" + request.getRequestId();

        Map<String, Object> root = new LinkedHashMap<>();
        // Fallback text — also what notifications and unfurls show.
        root.put("text", "New access request: " + request.getResourceName() + " (" + requester + ")");

        List<Object> blocks = new ArrayList<>();
        blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn",
                        "text", "*New access request*\n*App:* " + request.getResourceName()
                                + "\n*Requested by:* " + requester)));
        blocks.add(Map.of(
                "type", "context",
                "elements", List.of(Map.of("type", "mrkdwn",
                        "text", "Choose an action to review it in the Access Approval Tool. "
                                + "You will be asked to sign in."))));

        blocks.add(Map.of("type", "actions", "block_id", "decision", "elements", List.of(
                Map.of("type", "button", "style", "primary",
                        "text", Map.of("type", "plain_text", "text", "✓ Approve…"),
                        "url", link + "?action=approve"),
                Map.of("type", "button", "style", "danger",
                        "text", Map.of("type", "plain_text", "text", "✗ Reject…"),
                        "url", link + "?action=reject"),
                Map.of("type", "button",
                        "text", Map.of("type", "plain_text", "text", "Open request"),
                        "url", link))));

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

    /**
     * Notifies that a time-bound (JIT) grant expired and the user's access was
     * revoked in Omnissa Access (#49). Gated by webhook.notify-lifecycle.
     */
    public void notifyRevoked(CalloutRequest request) {
        if (request == null || webhookUrl == null || webhookUrl.isBlank() || !notifyLifecycle) {
            return;
        }
        postAsync(buildRevokedPayload(request), request.getRequestId());
    }

    /**
     * Notifies that a re-requestable grant's hold elapsed and the app is
     * requestable again (#49). Gated by webhook.notify-lifecycle.
     */
    public void notifyReopened(CalloutRequest request) {
        if (request == null || webhookUrl == null || webhookUrl.isBlank() || !notifyLifecycle) {
            return;
        }
        postAsync(buildReopenedPayload(request), request.getRequestId());
    }

    Map<String, Object> buildRevokedPayload(CalloutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = resolvedFormat();
        String ttl = request.getAccessTtlMinutes() != null
                ? " (" + request.getAccessTtlMinutes() + " min limit)" : "";
        if ("slack".equals(format) || "teams".equals(format)) {
            payload.put("text", "Access expired: " + request.getResourceName()
                    + " — access for " + requesterLabel(request) + " was revoked in Omnissa Access" + ttl
                    + (Boolean.TRUE.equals(request.getReRequestable())
                        ? "; the app will become requestable again shortly."
                        : "; this was a one-time grant and will not reappear."));
        } else {
            payload.put("event", "access.revoked");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("requester", requesterLabel(request));
            payload.put("accessTtlMinutes", request.getAccessTtlMinutes());
            payload.put("reRequestable", request.getReRequestable());
            payload.put("revokedDate", request.getRevokedAt() != null
                    ? request.getRevokedAt().toInstant().toString() : Instant.now().toString());
        }
        return payload;
    }

    Map<String, Object> buildReopenedPayload(CalloutRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String format = resolvedFormat();
        if ("slack".equals(format) || "teams".equals(format)) {
            payload.put("text", request.getResourceName() + " is requestable again for "
                    + requesterLabel(request) + " — the temporary exclusion was lifted.");
        } else {
            payload.put("event", "access.reopened");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("requester", requesterLabel(request));
            payload.put("reopenedDate", request.getRestoredAt() != null
                    ? request.getRestoredAt().toInstant().toString() : Instant.now().toString());
        }
        return payload;
    }

    private void postAsync(Object payload, String requestId) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                // Pass a URI, not a String: RestTemplate treats a String as a URI
                // TEMPLATE and re-encodes it, so a webhook URL carrying an
                // already-encoded query (e.g. Power Automate's
                // "sp=%2Ftriggers%2Fmanual%2Frun&sig=…") becomes "%252F…" and the
                // signature no longer matches — the post is rejected 401.
                restTemplate.postForEntity(URI.create(webhookUrl),
                        new HttpEntity<>(payload, headers), String.class);
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
                    + " requested by " + requesterLabel(request)
                    + " — approve or reject in the Access Approval Tool.");
        } else {
            payload.put("event", "request.created");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("requester", requesterLabel(request));
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
                    + " (" + requesterLabel(request) + ")");
        } else {
            payload.put("event", "request.decided");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("requester", requesterLabel(request));
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
                    + request.getResourceName() + " (" + requesterLabel(request) + ")");
        } else {
            payload.put("event", "request.expired");
            payload.put("requestId", request.getRequestId());
            payload.put("resourceName", request.getResourceName());
            payload.put("userId", request.getUserId());
            payload.put("requester", requesterLabel(request));
            payload.put("detail", "decision could not be delivered — request no longer exists in Omnissa Access");
        }
        return payload;
    }
}
