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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slack payload (#50) must SERIALIZE to a body carrying a top-level
 * {@code text} fallback plus a {@code blocks} array. Asserting on the
 * serialized bytes (not just the object) is deliberate: the payload was once
 * built as a Jackson 2 {@code ObjectNode}, which Spring Boot 4's Jackson 3
 * converter wrote out as an opaque POJO — Slack rejected it with
 * {@code 400 "no_text"} and the approval message silently never appeared.
 *
 * <p>Buttons are deep links (#52). The interactive variant decided inside a
 * callback where no signed-in user existed, so authorization came from a
 * separate approver map that failed open against Omnissa Access group
 * membership.
 */
class SlackActionablePayloadTest {

    private static final String BASE = "https://approvals.example.com";

    private CalloutRequest request() {
        HashMap<String, List<String>> attrs = new HashMap<>();
        attrs.put("firstName", List.of("Dean"));
        attrs.put("lastName", List.of("Flaming"));
        attrs.put("email", List.of("dean@flaming.ws"));
        return new CalloutRequest(CalloutOperation.activation, "req-123", "app-uuid",
                "I Am Showcase (Access)", "751802", attrs, null, null, null, null, null);
    }

    private WebhookNotifier notifier() {
        WebhookNotifier notifier = new WebhookNotifier();
        ReflectionTestUtils.setField(notifier, "webhookFormat", "slack");
        ReflectionTestUtils.setField(notifier, "slackActionable", true);
        ReflectionTestUtils.setField(notifier, "appBaseUrl", BASE);
        return notifier;
    }

    /**
     * Serialize with the Jackson 3 converter Spring Boot 4 actually uses for
     * outbound bodies — the one that turned the old Jackson 2 ObjectNode into an
     * opaque POJO. Using the Jackson 2 converter here would hide that failure.
     */
    private String serialized(Object payload) throws Exception {
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        new JacksonJsonHttpMessageConverter().write(payload, MediaType.APPLICATION_JSON, out);
        return out.getBodyAsString(StandardCharsets.UTF_8);
    }

    @Test
    void serializedMessageHasTextFallbackAndBlocks() throws Exception {
        Map<String, Object> payload = notifier().buildSlackActionableMessage(request());
        String json = serialized(payload);

        // The exact field whose absence produced Slack's 400 "no_text".
        assertTrue(json.contains("\"text\""), "serialized body must contain text: " + json);
        assertFalse(String.valueOf(payload.get("text")).isBlank(), "text must not be blank");
        assertTrue(json.contains("\"blocks\""), "serialized body must contain blocks: " + json);

        // Requester humanized, not the numeric id.
        assertTrue(json.contains("Dean Flaming"), "requester should be humanized: " + json);
    }

    @Test
    void buttonsAreDeepLinksCarryingTheDecision() throws Exception {
        String json = serialized(notifier().buildSlackActionableMessage(request()));

        assertTrue(json.contains(BASE + "/requests/req-123?action=approve"), json);
        assertTrue(json.contains(BASE + "/requests/req-123?action=reject"), json);
        assertTrue(json.contains(BASE + "/requests/req-123\""), "plain open link expected: " + json);
    }

    /**
     * The whole point of the #52 Slack change: no interaction callback, so no
     * endpoint at which authorization has to be re-derived from a separate map.
     */
    @Test
    void carriesNoInteractiveElements() throws Exception {
        String json = serialized(notifier().buildSlackActionableMessage(request()));

        assertFalse(json.contains("action_id"), "an action_id means a callback: " + json);
        assertFalse(json.contains("static_select"), json);
        assertFalse(json.contains("\"value\""),
                "button values existed to carry the requestId into a callback: " + json);
    }

    /**
     * Without app.base-url the buttons would point nowhere, so the notifier must
     * fall back to the plain-text notification rather than emit dead links.
     */
    @Test
    void blankBaseUrlFallsBackToPlainText() {
        WebhookNotifier notifier = new WebhookNotifier();
        ReflectionTestUtils.setField(notifier, "webhookFormat", "slack");
        ReflectionTestUtils.setField(notifier, "slackActionable", true);
        ReflectionTestUtils.setField(notifier, "appBaseUrl", "");

        Map<String, Object> payload = notifier.buildNewRequestPayload(request());
        assertTrue(payload.containsKey("text"), "plain-text fallback expected: " + payload);
        assertFalse(payload.containsKey("blocks"), "no buttons without a base URL: " + payload);
    }

    @Test
    void trailingSlashOnBaseUrlDoesNotDoubleUp() throws Exception {
        WebhookNotifier notifier = notifier();
        ReflectionTestUtils.setField(notifier, "appBaseUrl", BASE + "/");

        String json = serialized(notifier.buildSlackActionableMessage(request()));
        assertFalse(json.contains("//requests/"), "double slash in deep link: " + json);
        assertTrue(json.contains(BASE + "/requests/req-123"), json);
    }
}
