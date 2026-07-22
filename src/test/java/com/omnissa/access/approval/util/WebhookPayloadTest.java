package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookPayloadTest {

    private static CalloutRequest request() {
        return new CalloutRequest(CalloutOperation.activation, "req-42", "uuid-9",
                "Salesforce", "jdoe", null, null, null, null, null, null);
    }

    private static WebhookNotifier notifier(String format) {
        WebhookNotifier n = new WebhookNotifier();
        ReflectionTestUtils.setField(n, "webhookFormat", format);
        return n;
    }

    // ---- new request ----

    @Test
    void genericNewRequestPayload() {
        Map<String, Object> p = notifier("generic").buildNewRequestPayload(request());
        assertThat(p).containsEntry("event", "request.created")
                .containsEntry("requestId", "req-42")
                .containsEntry("resourceName", "Salesforce")
                .containsEntry("userId", "jdoe")
                .containsEntry("operation", "activation");
        assertThat(p).containsKey("receivedDate");
    }

    @Test
    void slackNewRequestPayloadIsText() {
        Map<String, Object> p = notifier("slack").buildNewRequestPayload(request());
        assertThat(p).containsOnlyKeys("text");
        assertThat((String) p.get("text")).contains("New access request")
                .contains("Salesforce").contains("jdoe");
    }

    @Test
    void teamsNewRequestPayloadIsText() {
        Map<String, Object> p = notifier("teams").buildNewRequestPayload(request());
        assertThat(p).containsOnlyKeys("text");
        assertThat((String) p.get("text")).contains("Salesforce");
    }

    @Test
    void nullFormatDefaultsToGeneric() {
        Map<String, Object> p = notifier(null).buildNewRequestPayload(request());
        assertThat(p).containsEntry("event", "request.created");
    }

    @Test
    void formatIsCaseInsensitiveAndTrimmed() {
        Map<String, Object> p = notifier("  SLACK  ").buildNewRequestPayload(request());
        assertThat(p).containsOnlyKeys("text");
    }

    // ---- decision ----

    @Test
    void genericHumanDecisionPayload() {
        Map<String, Object> p = notifier("generic")
                .buildDecisionPayload(request(), true, "alice", null);
        assertThat(p).containsEntry("event", "request.decided")
                .containsEntry("requestId", "req-42")
                .containsEntry("decision", "approved")
                .containsEntry("decidedBy", "alice");
        assertThat(p).doesNotContainKey("rule");
        assertThat(p).containsKey("decidedDate");
    }

    @Test
    void genericRuleDecisionIncludesRuleLabel() {
        Map<String, Object> p = notifier("generic")
                .buildDecisionPayload(request(), false, "auto-approval-rule", "#7");
        assertThat(p).containsEntry("decision", "rejected")
                .containsEntry("rule", "#7");
    }

    @Test
    void slackHumanApprovalText() {
        Map<String, Object> p = notifier("slack")
                .buildDecisionPayload(request(), true, "alice", null);
        assertThat((String) p.get("text")).contains("Approved by alice")
                .contains("Salesforce").contains("jdoe");
    }

    @Test
    void slackRuleRejectionText() {
        Map<String, Object> p = notifier("slack")
                .buildDecisionPayload(request(), false, "auto-approval-rule", "#7");
        assertThat((String) p.get("text")).contains("Auto-Rejected by rule #7");
    }

    // ---- expired ----

    @Test
    void genericExpiredPayload() {
        Map<String, Object> p = notifier("generic").buildExpiredPayload(request());
        assertThat(p).containsEntry("event", "request.expired")
                .containsEntry("requestId", "req-42")
                .containsEntry("resourceName", "Salesforce")
                .containsEntry("userId", "jdoe");
        assertThat((String) p.get("detail")).contains("no longer exists");
    }

    @Test
    void slackExpiredText() {
        Map<String, Object> p = notifier("slack").buildExpiredPayload(request());
        assertThat((String) p.get("text")).contains("could not be delivered")
                .contains("Salesforce");
    }
}
