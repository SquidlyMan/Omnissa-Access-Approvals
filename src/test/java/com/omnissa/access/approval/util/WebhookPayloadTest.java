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

    /** A request carrying the identity attributes Omnissa Access actually sends. */
    private static CalloutRequest requestWithIdentity() {
        java.util.HashMap<String, java.util.List<String>> attrs = new java.util.HashMap<>();
        attrs.put("firstName", java.util.List.of("Dean"));
        attrs.put("lastName", java.util.List.of("Flaming"));
        attrs.put("email", java.util.List.of("dean@flaming.ws"));
        return new CalloutRequest(CalloutOperation.activation, "req-42", "uuid-9",
                "Salesforce", "751802", attrs, null, null, null, null, null);
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

    /**
     * Teams needs the Adaptive Card envelope, not Slack's bare text. The
     * retired Office 365 connector accepted both, which is why this asserted
     * {@code text} and passed while Teams silently received nothing.
     */
    @Test
    void teamsNewRequestPayloadIsACard() {
        Map<String, Object> p = notifier("teams").buildNewRequestPayload(request());
        assertThat(p).containsOnlyKeys("type", "attachments");
        assertThat(p).containsEntry("type", "message");
        assertThat(p.toString()).contains("Salesforce").contains("AdaptiveCard");
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

    // ---- requester is shown by name/email, not the opaque numeric userId ----

    @Test
    void chatPayloadsNameTheRequesterInsteadOfNumericId() {
        CalloutRequest req = requestWithIdentity();
        assertThat((String) notifier("slack").buildNewRequestPayload(req).get("text"))
                .contains("Dean Flaming").doesNotContain("751802");
        assertThat((String) notifier("slack").buildDecisionPayload(req, true, "alice", null).get("text"))
                .contains("Dean Flaming").doesNotContain("751802");
        assertThat((String) notifier("slack").buildExpiredPayload(req).get("text"))
                .contains("Dean Flaming").doesNotContain("751802");
    }

    @Test
    void genericPayloadsAddRequesterAlongsideUserId() {
        // userId is kept for existing consumers; requester is additive.
        CalloutRequest req = requestWithIdentity();
        assertThat(notifier("generic").buildNewRequestPayload(req))
                .containsEntry("userId", "751802").containsEntry("requester", "Dean Flaming");
        assertThat(notifier("generic").buildDecisionPayload(req, true, "alice", null))
                .containsEntry("requester", "Dean Flaming");
        assertThat(notifier("generic").buildExpiredPayload(req))
                .containsEntry("requester", "Dean Flaming");
    }

    @Test
    void requesterFallsBackToEmailThenUserId() {
        java.util.HashMap<String, java.util.List<String>> emailOnly = new java.util.HashMap<>();
        emailOnly.put("email", java.util.List.of("dean@flaming.ws"));
        CalloutRequest byEmail = new CalloutRequest(CalloutOperation.activation, "r", "u",
                "Salesforce", "751802", emailOnly, null, null, null, null, null);
        assertThat((String) notifier("slack").buildNewRequestPayload(byEmail).get("text"))
                .contains("dean@flaming.ws");

        // No attributes at all — the numeric id is all we have.
        assertThat((String) notifier("slack").buildNewRequestPayload(request()).get("text"))
                .contains("jdoe");
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

    // ---- JIT lifecycle (#49): revoked / re-opened ----

    private static CalloutRequest timedGrant(boolean reRequestable) {
        CalloutRequest r = requestWithIdentity();
        r.setAccessTtlMinutes(5);
        r.setReRequestable(reRequestable);
        return r;
    }

    @Test
    void slackRevokedTextNamesRequesterAndTtl() {
        String text = (String) notifier("slack").buildRevokedPayload(timedGrant(true)).get("text");
        assertThat(text).contains("Access expired").contains("Salesforce")
                .contains("Dean Flaming").contains("5 min")
                .contains("requestable again");
    }

    @Test
    void slackRevokedTextSaysPermanentForOneTimeGrant() {
        String text = (String) notifier("slack").buildRevokedPayload(timedGrant(false)).get("text");
        assertThat(text).contains("one-time").doesNotContain("requestable again");
    }

    @Test
    void genericRevokedPayloadCarriesLifecycleFields() {
        Map<String, Object> p = notifier("generic").buildRevokedPayload(timedGrant(true));
        assertThat(p).containsEntry("event", "access.revoked")
                .containsEntry("resourceName", "Salesforce")
                .containsEntry("requester", "Dean Flaming")
                .containsEntry("accessTtlMinutes", 5)
                .containsEntry("reRequestable", true);
        assertThat(p).containsKey("revokedDate");
    }

    @Test
    void reopenedPayloads() {
        String text = (String) notifier("slack").buildReopenedPayload(timedGrant(true)).get("text");
        assertThat(text).contains("requestable again").contains("Dean Flaming").contains("Salesforce");

        Map<String, Object> p = notifier("generic").buildReopenedPayload(timedGrant(true));
        assertThat(p).containsEntry("event", "access.reopened")
                .containsEntry("requester", "Dean Flaming");
        assertThat(p).containsKey("reopenedDate");
    }

    @Test
    void lifecycleNotificationsAreSuppressedWhenDisabled() {
        // notify-lifecycle=false must skip the POST entirely (no URL configured
        // here either, but the flag is the guard under test).
        WebhookNotifier n = notifier("slack");
        ReflectionTestUtils.setField(n, "webhookUrl", "https://hooks.example.invalid/x");
        ReflectionTestUtils.setField(n, "notifyLifecycle", false);
        // Should return without attempting delivery; failure would surface as an exception.
        n.notifyRevoked(timedGrant(true));
        n.notifyReopened(timedGrant(true));
    }

    @Test
    void slackExpiredText() {
        Map<String, Object> p = notifier("slack").buildExpiredPayload(request());
        assertThat((String) p.get("text")).contains("could not be delivered")
                .contains("Salesforce");
    }

    // ── Decision detail (#57 follow-up) ──────────────────────────────────────
    // "Rejected" alone does not distinguish a decline the user can retry from
    // one that blocks them, and "Approved" does not say whether access is
    // permanent or expires. Admins watching the channel need the consequence.

    private CalloutRequest decided(Integer ttlMinutes, Boolean reRequestable) {
        CalloutRequest r = request();
        r.setAccessTtlMinutes(ttlMinutes);
        r.setReRequestable(reRequestable);
        return r;
    }

    @Test
    void approvedPermanentlyIsSpelledOut() {
        String text = (String) notifier("slack")
                .buildDecisionPayload(decided(null, null), true, "dean", null).get("text");
        assertThat(text).contains("Approved by dean").contains("permanent access");
    }

    @Test
    void approvedWithTtlStatesDurationAndWhatFollows() {
        String reRequestable = (String) notifier("slack")
                .buildDecisionPayload(decided(5, true), true, "dean", null).get("text");
        assertThat(reRequestable).contains("5 minutes").contains("requestable again");

        String oneTime = (String) notifier("slack")
                .buildDecisionPayload(decided(60, false), true, "dean", null).get("text");
        assertThat(oneTime).contains("1 hour").contains("one-time");
    }

    @Test
    void rejectionDistinguishesTemporaryFromBlocking() {
        String temporary = (String) notifier("slack")
                .buildDecisionPayload(decided(null, true), false, "dean", null).get("text");
        assertThat(temporary).contains("temporary").contains("may request again");

        String permanent = (String) notifier("slack")
                .buildDecisionPayload(decided(null, false), false, "dean", null).get("text");
        assertThat(permanent).contains("permanent").contains("blocked from re-requesting");
    }

    @Test
    void teamsCarriesTheSameDetail() {
        String payload = notifier("teams")
                .buildDecisionPayload(decided(1440, true), true, "dean", null).toString();
        assertThat(payload).contains("1 day").contains("requestable again");
    }

    @Test
    void genericPayloadExposesTheDetailStructurally() {
        Map<String, Object> p = notifier("generic")
                .buildDecisionPayload(decided(5, false), true, "dean", null);
        assertThat(p).containsEntry("accessTtlMinutes", 5)
                .containsEntry("reRequestable", false)
                .containsEntry("permanent", false);
    }

    @Test
    void durationsReadNaturally() {
        assertThat(WebhookNotifier.humanDuration(1)).isEqualTo("1 minute");
        assertThat(WebhookNotifier.humanDuration(5)).isEqualTo("5 minutes");
        assertThat(WebhookNotifier.humanDuration(60)).isEqualTo("1 hour");
        assertThat(WebhookNotifier.humanDuration(480)).isEqualTo("8 hours");
        assertThat(WebhookNotifier.humanDuration(1440)).isEqualTo("1 day");
        assertThat(WebhookNotifier.humanDuration(10080)).isEqualTo("7 days");
    }
}
