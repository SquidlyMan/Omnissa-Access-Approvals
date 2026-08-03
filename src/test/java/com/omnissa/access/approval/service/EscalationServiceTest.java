package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.EscalationOutcome;
import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.HubNotificationOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.RuleEngine;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Escalation (#51). The failure this feature exists to prevent is a request
 * nobody looks at, so the tests that matter are the ones asserting it fires
 * when it should, exactly once, and never claims a nudge it did not send.
 */
class EscalationServiceTest {

    private final ApprovalsRepository repository = mock(ApprovalsRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final WebhookNotifier webhookNotifier = mock(WebhookNotifier.class);
    private final ApproverDirectoryService approverDirectory = mock(ApproverDirectoryService.class);
    private final HubNotificationService hubNotificationService = mock(HubNotificationService.class);
    private final EscalationService service = new EscalationService();

    {
        ReflectionTestUtils.setField(service, "approvalsRepository", repository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "webhookNotifier", webhookNotifier);
        ReflectionTestUtils.setField(service, "ruleEngine", new RuleEngine());
        ReflectionTestUtils.setField(service, "approverDirectory", approverDirectory);
        ReflectionTestUtils.setField(service, "hubNotificationService", hubNotificationService);
        ReflectionTestUtils.setField(service, "sseController", mock(SseController.class));
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://approvals.example.com");
    }

    private static AutoRule escalationRule(int afterMinutes, int expiryDays) {
        AutoRule rule = new AutoRule();
        rule.setId(1L);
        rule.setEnabled(true);
        rule.setAction("reject");
        rule.setExpiryDays(expiryDays);
        rule.setEscalateAfterMinutes(afterMinutes);
        return rule;
    }

    private static CalloutRequest pending(String id, int minutesAgo, String appName) {
        CalloutRequest r = new CalloutRequest(CalloutOperation.activation, id, "uuid-" + id,
                appName, "jdoe", null, null, null, null, null, null);
        r.setState("pending");
        r.setReceivedDate(Date.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
        return r;
    }

    private void stubRepo(CalloutRequest... requests) {
        when(repository.findByState("pending")).thenReturn(List.of(requests));
        for (CalloutRequest r : requests) {
            when(repository.findByRequestId(r.getRequestId())).thenReturn(r);
        }
    }

    @Test
    @DisplayName("a request past the threshold escalates and is marked once")
    void escalatesPastThreshold() {
        CalloutRequest stale = pending("req-1", 300, "Salesforce");
        stubRepo(stale);
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.SENT);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of());

        assertThat(service.escalateFor(escalationRule(240, 3))).isEqualTo(1);
        assertThat(stale.getEscalationStage()).isEqualTo(1);
        assertThat(stale.getEscalatedAt()).isNotNull();
        verify(repository).save(stale);
    }

    @Test
    @DisplayName("a request younger than the threshold is left alone")
    void ignoresYoungRequests() {
        CalloutRequest fresh = pending("req-1", 10, "Salesforce");
        stubRepo(fresh);

        assertThat(service.escalateFor(escalationRule(240, 3))).isZero();
        assertThat(fresh.getEscalationStage()).isNull();
        verify(webhookNotifier, never()).notifyEscalated(any(), anyInt());
    }

    @Test
    @DisplayName("an already-escalated request does not fire a second time")
    void firesOnlyOnce() {
        CalloutRequest already = pending("req-1", 300, "Salesforce");
        already.setEscalationStage(1);
        stubRepo(already);

        assertThat(service.escalateFor(escalationRule(240, 3))).isZero();
        verify(webhookNotifier, never()).notifyEscalated(any(), anyInt());
    }

    @Test
    @DisplayName("a request decided mid-pass is skipped rather than nudged")
    void skipsRequestDecidedMidPass() {
        CalloutRequest stale = pending("req-1", 300, "Salesforce");
        when(repository.findByState("pending")).thenReturn(List.of(stale));
        // Re-fetch returns it already approved — a human acted during the pass.
        CalloutRequest decided = pending("req-1", 300, "Salesforce");
        decided.setState("approved");
        when(repository.findByRequestId("req-1")).thenReturn(decided);

        assertThat(service.escalateFor(escalationRule(240, 3))).isZero();
        verify(webhookNotifier, never()).notifyEscalated(any(), anyInt());
    }

    @Test
    @DisplayName("the rule's app pattern scopes which requests escalate")
    void honoursRuleScoping() {
        CalloutRequest salesforce = pending("req-1", 300, "Salesforce");
        CalloutRequest workday = pending("req-2", 300, "Workday");
        stubRepo(salesforce, workday);
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.SENT);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of());

        AutoRule scoped = escalationRule(240, 3);
        scoped.setAppPattern("Salesforce");

        assertThat(service.escalateFor(scoped)).isEqualTo(1);
        assertThat(salesforce.getEscalationStage()).isEqualTo(1);
        assertThat(workday.getEscalationStage())
                .as("a rule scoped to Salesforce must not escalate Workday")
                .isNull();
    }

    @Test
    @DisplayName("NOT_CONFIGURED still advances the stage, and the audit does not claim a nudge was sent")
    void notConfiguredAdvancesWithoutClaimingDelivery() {
        CalloutRequest stale = pending("req-1", 300, "Salesforce");
        stubRepo(stale);
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.NOT_CONFIGURED);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of());

        assertThat(service.escalateFor(escalationRule(240, 3))).isEqualTo(1);
        assertThat(stale.getEscalationStage())
                .as("nothing is configured to receive it, so there is nothing to retry")
                .isEqualTo(1);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordFor(eq("request-escalated"), eq(stale), note.capture());
        assertThat(note.getValue()).contains("no chat channel configured");
        assertThat(note.getValue()).doesNotContain("chat channel notified");
    }

    @Test
    @DisplayName("a total delivery failure leaves the stage un-advanced so the next pass retries")
    void failureLeavesStageUnadvanced() {
        CalloutRequest stale = pending("req-1", 300, "Salesforce");
        stubRepo(stale);
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.FAILED);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of());

        assertThat(service.escalateFor(escalationRule(240, 3))).isZero();
        assertThat(stale.getEscalationStage())
                .as("a missed summons is the failure this feature exists to prevent — it must retry")
                .isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a chat failure still counts as escalated if approvers were reached directly")
    void approverNotificationRescuesAChannelFailure() {
        CalloutRequest stale = pending("req-1", 300, "Salesforce");
        stubRepo(stale);
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.FAILED);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of(
                new GroupMember("u1", "Jane Doe", "jdoe", "jane@corp.com", null)));
        when(hubNotificationService.notifyUsers(anyList(), anyString(), anyString(), any()))
                .thenReturn(Map.of("u1", HubNotificationOutcome.SENT));

        assertThat(service.escalateFor(escalationRule(240, 3))).isEqualTo(1);
        assertThat(stale.getEscalationStage()).isEqualTo(1);
    }

    @Test
    @DisplayName("approvers are resolved once per pass, not once per request")
    void resolvesApproverPoolOncePerPass() {
        stubRepo(pending("req-1", 300, "A"), pending("req-2", 300, "B"), pending("req-3", 300, "C"));
        when(webhookNotifier.notifyEscalated(any(), anyInt())).thenReturn(EscalationOutcome.SENT);
        when(approverDirectory.escalationRecipients()).thenReturn(List.of());

        service.escalateFor(escalationRule(240, 3));

        verify(approverDirectory, org.mockito.Mockito.times(1)).escalationRecipients();
    }

    // --- claim TTL ---

    @Test
    @DisplayName("a claim older than its TTL is released and audited as system")
    void releasesStaleClaim() {
        CalloutRequest held = pending("req-1", 300, "Salesforce");
        held.setAssignedOwner("alice");
        held.setAssignedAt(Date.from(Instant.now().minus(120, ChronoUnit.MINUTES)));
        stubRepo(held);

        AutoRule rule = escalationRule(240, 3);
        rule.setClaimTtlMinutes(60);

        assertThat(service.releaseStaleClaims(rule)).isEqualTo(1);
        assertThat(held.getAssignedOwner()).isNull();
        assertThat(held.getAssignedAt()).isNull();
        verify(auditService).recordFor(eq("request-released"), eq(held), anyString(), eq("system"));
    }

    @Test
    @DisplayName("a fresh claim is left alone")
    void keepsFreshClaim() {
        CalloutRequest held = pending("req-1", 300, "Salesforce");
        held.setAssignedOwner("alice");
        held.setAssignedAt(Date.from(Instant.now().minus(5, ChronoUnit.MINUTES)));
        stubRepo(held);

        AutoRule rule = escalationRule(240, 3);
        rule.setClaimTtlMinutes(60);

        assertThat(service.releaseStaleClaims(rule)).isZero();
        assertThat(held.getAssignedOwner()).isEqualTo("alice");
    }

    @Test
    @DisplayName("claim TTL falls back to the escalation interval when unset")
    void claimTtlInheritsEscalationInterval() {
        AutoRule rule = escalationRule(240, 3);
        assertThat(rule.effectiveClaimTtlMinutes()).isEqualTo(240);
        rule.setClaimTtlMinutes(30);
        assertThat(rule.effectiveClaimTtlMinutes()).isEqualTo(30);
    }
}
