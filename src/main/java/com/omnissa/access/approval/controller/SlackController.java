package com.omnissa.access.approval.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.service.DecisionService;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.SlackSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Inbound Slack interactivity endpoint (#50) — approve/reject from chat.
 *
 * <p>Public + unauthenticated at the session layer (like the Access callout),
 * but the caller is authenticated <b>cryptographically</b>: every request's
 * Slack signature is verified before any state change (design §2.2). The
 * clicking Slack user is then mapped to an app approver identity — a valid
 * signature proves the message came from the workspace, <em>not</em> that the
 * clicker may approve, so unmapped users are rejected. Decisions run through the
 * shared {@link DecisionService} with the resolved Slack identity as attribution.
 */
@RestController
@RequestMapping("/api/slack")
public class SlackController {

    private static final Logger logger = LoggerFactory.getLogger(SlackController.class);

    @Value("${slack.signing-secret:}")
    private String signingSecret;

    /** "slackUserId:appIdentity" pairs, comma-separated. e.g. U0123:dean@flaming.ws,U0456:jane */
    @Value("${slack.approver-map:}")
    private String approverMapRaw;

    @Autowired private DecisionService decisionService;
    @Autowired private AuditService auditService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/interactions")
    public ResponseEntity<?> interactions(HttpServletRequest request) {
        String rawBody;
        try {
            rawBody = request.getReader().lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String ts = request.getHeader("X-Slack-Request-Timestamp");
        String sig = request.getHeader("X-Slack-Signature");
        if (!SlackSignatureVerifier.isValid(signingSecret, ts, sig, rawBody, Instant.now().getEpochSecond())) {
            logger.warn("Slack interaction rejected — invalid/absent signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode payload;
        try {
            // Body is "payload=<url-encoded-json>".
            String json = rawBody.startsWith("payload=")
                    ? URLDecoder.decode(rawBody.substring("payload=".length()), StandardCharsets.UTF_8)
                    : rawBody;
            payload = mapper.readTree(json);
        } catch (Exception e) {
            logger.warn("Slack interaction: unparseable payload: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }

        JsonNode action = payload.path("actions").path(0);
        String actionId = action.path("action_id").asText("");
        String responseUrl = payload.path("response_url").asText(null);
        String slackUserId = payload.path("user").path("id").asText("");

        // The duration select fires its own interaction on change — just ack it;
        // we read the chosen value from `state` when a button is clicked.
        if (!"approve".equals(actionId) && !"reject".equals(actionId)) {
            return ResponseEntity.ok().build();
        }

        String requestId = action.path("value").asText("");
        String approver = resolveApprover(slackUserId);
        if (approver == null) {
            logger.warn("Slack decision from unmapped user {} for requestId={} — rejected", slackUserId, requestId);
            auditService.record("slack-rejected", requestId, null,
                    "Unmapped Slack user " + slackUserId + " attempted a decision — not an authorized approver");
            replaceMessage(responseUrl, ":no_entry: You are not an authorized approver in the Access Approval Tool.");
            return ResponseEntity.ok().build();
        }

        boolean approved = "approve".equals(actionId);
        Integer ttlMinutes = null;
        if (approved) {
            int minutes = parseInt(payload.path("state").path("values")
                    .path("jit_duration").path("duration").path("selected_option").path("value").asText("0"));
            ttlMinutes = minutes > 0 ? minutes : null;
        }

        String decider = approver + " (via Slack)";
        DecisionOutcome outcome;
        try {
            outcome = decisionService.decide(requestId, approved, "Decided from Slack", ttlMinutes, null, decider);
        } catch (Exception e) {
            logger.error("Slack decision failed for requestId={}", requestId, e);
            replaceMessage(responseUrl, ":warning: Something went wrong applying the decision. Check the tool.");
            return ResponseEntity.ok().build();
        }

        replaceMessage(responseUrl, slackResultText(outcome, approved, approver, ttlMinutes));
        return ResponseEntity.ok().build();
    }

    private String slackResultText(DecisionOutcome outcome, boolean approved, String approver, Integer ttlMinutes) {
        return switch (outcome) {
            case DELIVERED -> (approved ? ":white_check_mark: *Approved*" : ":x: *Rejected*")
                    + " by " + approver
                    + (approved && ttlMinutes != null ? "  ·  time-bound " + ttlMinutes + " min" : "");
            case EXPIRED -> ":warning: Could not deliver — the request no longer exists in Omnissa Access.";
            case UNREACHABLE -> ":warning: Omnissa Access is unreachable — decision not delivered. Try again from the tool.";
        };
    }

    /** Replace the original Slack message via the interaction's response_url. */
    private void replaceMessage(String responseUrl, String text) {
        if (responseUrl == null || responseUrl.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("replace_original", true);
            body.put("text", text);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(responseUrl, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            logger.warn("Slack message update failed: {}", e.getMessage());
        }
    }

    /** Map a Slack user id to an app approver identity, or null if not authorized. */
    String resolveApprover(String slackUserId) {
        return mapApprover(approverMapRaw, slackUserId);
    }

    /**
     * Resolve a Slack user id against a {@code "slackId:appIdentity,…"} map.
     * Returns the app identity, or null if the map/id is blank or unmapped —
     * unmapped users must never be granted decision rights. Pure/static for tests.
     */
    static String mapApprover(String mapRaw, String slackUserId) {
        if (slackUserId == null || slackUserId.isBlank() || mapRaw == null || mapRaw.isBlank()) {
            return null;
        }
        for (String pair : mapRaw.split(",")) {
            String[] kv = pair.trim().split(":", 2);
            if (kv.length == 2 && kv[0].trim().equals(slackUserId) && !kv[1].trim().isBlank()) {
                return kv[1].trim();
            }
        }
        return null;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
