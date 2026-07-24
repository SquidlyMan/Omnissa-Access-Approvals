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
 * The interactive Slack payload (#50) must SERIALIZE to a body carrying a
 * top-level {@code text} fallback plus a {@code blocks} array. Asserting on the
 * serialized bytes (not just the object) is deliberate: the payload was once
 * built as a Jackson 2 {@code ObjectNode}, which Spring Boot 4's Jackson 3
 * converter wrote out as an opaque POJO — Slack rejected it with
 * {@code 400 "no_text"} and the approval message silently never appeared.
 */
class SlackActionablePayloadTest {

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
        // Buttons carry the requestId so the interaction can resolve the request.
        assertTrue(json.contains("\"approve\"") && json.contains("\"reject\""), json);
        assertTrue(json.contains("req-123"), "buttons must carry requestId: " + json);
    }

    @Test
    void durationMenuOffersPermanentAndTimedOptions() throws Exception {
        Map<String, Object> payload = notifier().buildSlackActionableMessage(request());
        String json = serialized(payload);

        assertTrue(json.contains("jit_duration"), json);
        assertTrue(json.contains("static_select"), json);
        assertTrue(json.contains("\"0\""), "permanent option expected: " + json);
        assertTrue(json.contains("\"5\"") && json.contains("\"1440\""), json);

        // initial_option must match one of the options, or Slack rejects the blocks.
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) payload.get("blocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> select =
                (Map<String, Object>) ((List<Object>) ((Map<String, Object>) blocks.get(1)).get("elements")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> initial = (Map<String, Object>) select.get("initial_option");
        @SuppressWarnings("unchecked")
        List<Object> options = (List<Object>) select.get("options");
        assertEquals("0", initial.get("value"));
        assertTrue(options.contains(initial), "initial_option must be one of options");
    }
}
