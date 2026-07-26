package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Teams Adaptive Card (#55). Buttons are deep links rather than callbacks:
 * Office 365 connectors (which supported Action.Http) are retired, and a Power
 * Automate callback would need the premium HTTP connector. Serialized with the
 * Jackson 3 converter Spring Boot 4 actually uses — the Slack card once shipped
 * broken because a Jackson 2 tree serialized to an opaque POJO.
 */
class TeamsCardPayloadTest {

    private static final String BASE = "https://approvals.example.com";

    private CalloutRequest request() {
        HashMap<String, List<String>> attrs = new HashMap<>();
        attrs.put("firstName", List.of("Dean"));
        attrs.put("lastName", List.of("Flaming"));
        return new CalloutRequest(CalloutOperation.activation, "req-123", "app-uuid",
                "I Am Showcase (Access)", "751802", attrs, null, null, null, null, null);
    }

    private WebhookNotifier notifier(String baseUrl) {
        WebhookNotifier n = new WebhookNotifier();
        ReflectionTestUtils.setField(n, "webhookFormat", "teams");
        ReflectionTestUtils.setField(n, "teamsActionable", true);
        ReflectionTestUtils.setField(n, "appBaseUrl", baseUrl);
        return n;
    }

    private String serialized(Object payload) throws Exception {
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        new JacksonJsonHttpMessageConverter().write(payload, MediaType.APPLICATION_JSON, out);
        return out.getBodyAsString(StandardCharsets.UTF_8);
    }

    @Test
    void usesTheAttachmentEnvelopeAPowerAutomateWorkflowExpects() throws Exception {
        String json = serialized(notifier(BASE).buildTeamsActionableCard(request()));

        assertTrue(json.contains("\"type\":\"message\""), json);
        assertTrue(json.contains("application/vnd.microsoft.card.adaptive"), json);
        assertTrue(json.contains("\"AdaptiveCard\""), json);
    }

    @Test
    void buttonsDeepLinkToTheRequestWithThePreselectedDecision() throws Exception {
        String json = serialized(notifier(BASE).buildTeamsActionableCard(request()));

        assertTrue(json.contains(BASE + "/requests/req-123?action=approve"), json);
        assertTrue(json.contains(BASE + "/requests/req-123?action=reject"), json);
        assertTrue(json.contains(BASE + "/requests/req-123\""), "plain open link expected: " + json);
        // Deep links only — no callback action that would need a premium connector.
        assertTrue(json.contains("Action.OpenUrl"), json);
        assertFalse(json.contains("Action.Http"), "connectors are retired; Action.Http would not fire: " + json);
    }

    @Test
    void namesTheRequesterRatherThanTheNumericId() throws Exception {
        String json = serialized(notifier(BASE).buildTeamsActionableCard(request()));
        assertTrue(json.contains("Dean Flaming"), json);
        assertFalse(json.contains("751802"), json);
    }

    @Test
    void trailingSlashInBaseUrlDoesNotDoubleUp() throws Exception {
        String json = serialized(notifier(BASE + "/").buildTeamsActionableCard(request()));
        assertFalse(json.contains("//requests/"), "base URL slash mishandled: " + json);
        assertTrue(json.contains(BASE + "/requests/req-123"), json);
    }

    /**
     * Without a public URL there is nothing to link to, so the buttons are
     * dropped — but the notification must still be a card, or Power Automate
     * discards it and the channel gets nothing at all. This previously asserted
     * a bare {@code text} payload, which was precisely the shape that failed
     * silently.
     */
    @Test
    void fallsBackToACardWithoutButtonsWhenNoBaseUrlIsConfigured() {
        Map<String, Object> payload = notifier("").buildNewRequestPayload(request());

        assertEquals("message", payload.get("type"), payload.toString());
        assertFalse(payload.containsKey("text"), "a bare text payload is dropped: " + payload);
        assertFalse(payload.toString().contains("Action.OpenUrl"),
                "no base URL means no links: " + payload);
        assertTrue(payload.toString().contains("New access request"), payload.toString());
    }

    /**
     * Teams never received a decision, expiry, revoke or reopen notification:
     * those builders emitted Slack's bare {"text": …}, which the retired Office
     * 365 connector accepted but a Power Automate workflow silently drops. Only
     * the new-request card worked, because it was the one built as a card.
     */
    @Test
    void lifecycleNotificationsAreCardsNotBareText() {
        WebhookNotifier notifier = notifier("https://approvals.example.com");
        CalloutRequest request = request();

        for (Map<String, Object> payload : List.of(
                notifier.buildDecisionPayload(request, false, "dean@flaming.ws", null),
                notifier.buildRevokedPayload(request),
                notifier.buildReopenedPayload(request),
                notifier.buildNewRequestPayload(request))) {

            assertEquals("message", payload.get("type"),
                    "Power Automate needs the message envelope: " + payload);
            assertTrue(payload.containsKey("attachments"), "expected an attachments array: " + payload);
            assertFalse(payload.containsKey("text"),
                    "a bare text payload is dropped by the workflow: " + payload);
        }
    }

    @Test
    void decisionCardCarriesTheAttribution() {
        Map<String, Object> payload =
                notifier("https://approvals.example.com").buildDecisionPayload(request(), false, "dean@flaming.ws", null);

        assertTrue(payload.toString().contains("Rejected by dean@flaming.ws"), payload.toString());
        assertTrue(payload.toString().contains("I Am Showcase"), payload.toString());
    }

    @Test
    void slackKeepsBareTextAndIsNotWrapped() {
        WebhookNotifier slack = new WebhookNotifier();
        org.springframework.test.util.ReflectionTestUtils.setField(slack, "webhookFormat", "slack");

        Map<String, Object> payload = slack.buildDecisionPayload(request(), true, "dean", null);
        assertTrue(payload.containsKey("text"), "Slack renders a bare text payload: " + payload);
        assertFalse(payload.containsKey("attachments"), payload.toString());
    }
}
