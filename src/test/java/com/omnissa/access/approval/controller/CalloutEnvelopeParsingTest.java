package com.omnissa.access.approval.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApprovalController#parseCalloutBody(String)} — the callout
 * envelope-unwrapping logic that has repeatedly bitten this integration.
 */
class CalloutEnvelopeParsingTest {

    private static final String INNER_REQUEST_JSON = """
            {
              "operation": "activation",
              "requestId": "req-123",
              "resourceUuid": "uuid-abc",
              "resourceName": "Salesforce",
              "userId": "jdoe",
              "userAttributes": {"groupNames": ["admins", "users"]}
            }""";

    @Test
    void unwrapsMessagingEnvelopeWithBodyAsJsonEncodedString() throws Exception {
        // Access wraps the request: {"type":...,"body":"<json-encoded string>"}
        ObjectMapper mapper = new ObjectMapper();
        String encodedBody = mapper.writeValueAsString(INNER_REQUEST_JSON); // produces a JSON string literal
        String envelope = "{\"type\":\"activation.requested\",\"body\":" + encodedBody + "}";

        CalloutRequest result = ApprovalController.parseCalloutBody(envelope);

        assertThat(result).isNotNull();
        assertThat(result.getRequestId()).isEqualTo("req-123");
        assertThat(result.getResourceName()).isEqualTo("Salesforce");
        assertThat(result.getUserId()).isEqualTo("jdoe");
        assertThat(result.getOperation()).isEqualTo(CalloutOperation.activation);
        assertThat(result.getUserAttributes().get("groupNames")).containsExactly("admins", "users");
    }

    @Test
    void fallsBackToFlatFormatWhenNoEnvelope() {
        CalloutRequest result = ApprovalController.parseCalloutBody(INNER_REQUEST_JSON);

        assertThat(result).isNotNull();
        assertThat(result.getRequestId()).isEqualTo("req-123");
        assertThat(result.getResourceName()).isEqualTo("Salesforce");
        assertThat(result.getOperation()).isEqualTo(CalloutOperation.activation);
    }

    @Test
    void deactivationOperationParsed() {
        String flat = """
                {"operation":"deactivation","requestId":"req-9","resourceUuid":"u","userId":"jdoe"}""";
        CalloutRequest result = ApprovalController.parseCalloutBody(flat);
        assertThat(result).isNotNull();
        assertThat(result.getOperation()).isEqualTo(CalloutOperation.deactivation);
    }

    @Test
    void ignoresUnknownProperties() {
        String flat = """
                {"operation":"activation","requestId":"req-7","resourceUuid":"u","userId":"jdoe",
                 "somethingBrandNew":"whatever","nested":{"a":1}}""";
        CalloutRequest result = ApprovalController.parseCalloutBody(flat);
        assertThat(result).isNotNull();
        assertThat(result.getRequestId()).isEqualTo("req-7");
    }

    @Test
    void nullBodyReturnsNull() {
        assertThat(ApprovalController.parseCalloutBody(null)).isNull();
    }

    @Test
    void blankBodyReturnsNull() {
        assertThat(ApprovalController.parseCalloutBody("   ")).isNull();
    }

    @Test
    void emptyProbePostHasNoRequestId() {
        // Access sends an empty test POST when approvals settings are saved.
        CalloutRequest result = ApprovalController.parseCalloutBody("{}");
        // Either unparseable (null) or a hollow request with no requestId — both are "ignore".
        assertThat(result == null || result.getRequestId() == null).isTrue();
    }

    @Test
    void garbageBodyReturnsNull() {
        assertThat(ApprovalController.parseCalloutBody("not json at all")).isNull();
    }
}
