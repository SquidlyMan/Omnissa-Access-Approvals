package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.HubNotificationOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.omnissa.access.approval.model.HubNotificationOutcome.FAILED;
import static com.omnissa.access.approval.model.HubNotificationOutcome.SENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Hub Notification payload/response shapes, verified
 * against Omnissa's "Workspace ONE Notifications Service Guide" and a live
 * probe of this tenant's {@code /ws1notifications/api/v1/notifications}.
 */
class HubNotificationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildsTheMinimalRequiredCard() {
        Map<String, Object> card = HubNotificationService.buildCard("Title", "Description", null);

        Map<String, Object> header = (Map<String, Object>) card.get("header");
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        assertThat(header.get("title")).isEqualTo("Title");
        assertThat(body.get("description")).isEqualTo("Description");
        assertThat(card).doesNotContainKey("links");
        assertThat(card).doesNotContainKey("actions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeepLinkBecomesAPlainLinkNeverAnAction() {
        Map<String, Object> card = HubNotificationService.buildCard(
                "Title", "Description", "https://approvals.example.com/requests/abc");

        assertThat(card).doesNotContainKey("actions");
        List<Map<String, Object>> links = (List<Map<String, Object>>) card.get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("href")).isEqualTo("https://approvals.example.com/requests/abc");
    }

    @Test
    void parsesASuccessfulDistributedResponse() {
        String response = """
                {"abc123":{"status_code":"200","notification_id":"n1"},
                 "xyz456":{"status_code":"200","notification_id":"n2"}}""";

        Map<String, HubNotificationOutcome> outcomes = HubNotificationService.parseDistributedResponse(
                List.of("abc123", "xyz456"), response);

        assertThat(outcomes).containsEntry("abc123", SENT).containsEntry("xyz456", SENT);
    }

    @Test
    void aPerRecipientFailureIsReportedNotHiddenBehindOverallSuccess() {
        String response = """
                {"abc123":{"status_code":"200","notification_id":"n1"},
                 "xyz456":{"status_code":"404","error":"user not found"}}""";

        Map<String, HubNotificationOutcome> outcomes = HubNotificationService.parseDistributedResponse(
                List.of("abc123", "xyz456"), response);

        assertThat(outcomes).containsEntry("abc123", SENT).containsEntry("xyz456", FAILED);
    }

    @Test
    void aRecipientMissingFromTheResponseIsFailedNotAssumedSent() {
        String response = "{\"abc123\":{\"status_code\":\"200\"}}";

        Map<String, HubNotificationOutcome> outcomes = HubNotificationService.parseDistributedResponse(
                List.of("abc123", "xyz456"), response);

        assertThat(outcomes.get("xyz456")).isEqualTo(FAILED);
    }

    @Test
    void unparsableResponseFailsEveryRecipientRatherThanThrowing() {
        Map<String, HubNotificationOutcome> outcomes = HubNotificationService.parseDistributedResponse(
                List.of("abc123", "xyz456"), "not json");

        assertThat(outcomes.values()).containsOnly(FAILED);
    }
}
