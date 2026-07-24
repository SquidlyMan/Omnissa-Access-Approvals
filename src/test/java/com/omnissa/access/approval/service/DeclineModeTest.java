package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.interfaces.EntitlementsInterface;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.RevokeOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decline modes (#57). A temporary decline must leave entitlements untouched;
 * a permanent decline must exclude the user so the app cannot be re-requested —
 * and must NOT claim a block it failed to apply.
 */
class DeclineModeTest {

    private final EntitlementsInterface entitlements = mock(EntitlementsInterface.class);
    private final ApprovalsRepository repository = mock(ApprovalsRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final WebhookNotifier webhooks = mock(WebhookNotifier.class);
    private final SseController sse = mock(SseController.class);
    private final DecisionService service = new DecisionService();

    private CalloutRequest request;

    @BeforeEach
    void setUp() {
        request = new CalloutRequest(CalloutOperation.activation, "req-1", "app-uuid",
                "I Am Showcase", "751802", null, null, null, null, null, null);
        request.setState("rejected");
        when(repository.findByRequestId("req-1")).thenReturn(request);
        ReflectionTestUtils.setField(service, "entitlementsInterface", entitlements);
        ReflectionTestUtils.setField(service, "approvalsRepository", repository);
        ReflectionTestUtils.setField(service, "auditService", audit);
        ReflectionTestUtils.setField(service, "webhookNotifier", webhooks);
        ReflectionTestUtils.setField(service, "sseController", sse);
    }

    @Test
    void temporaryDeclineTouchesNoEntitlements() {
        String note = service.applyDecline("req-1", true, "dean");

        verify(entitlements, never()).revokeAccess(any());
        assertThat(note).isEmpty();
        assertThat(request.getReRequestable()).isTrue();
    }

    @Test
    void nullPolicyDefaultsToTemporary() {
        service.applyDecline("req-1", null, "dean");
        verify(entitlements, never()).revokeAccess(any());
        assertThat(request.getReRequestable()).isTrue();
    }

    @Test
    void permanentDeclineExcludesTheUser() {
        when(entitlements.revokeAccess(request)).thenReturn(RevokeOutcome.REVOKED);

        String note = service.applyDecline("req-1", false, "dean");

        verify(entitlements).revokeAccess(request);
        assertThat(request.getReRequestable()).isFalse();
        assertThat(request.getRevokedAt()).isNotNull();
        assertThat(note).contains("permanent");
        verify(audit).record(eq("access-blocked"), eq("req-1"), anyString(), anyString(), eq("dean"));
    }

    @Test
    void alreadyExcludedCountsAsBlocked() {
        when(entitlements.revokeAccess(request)).thenReturn(RevokeOutcome.ALREADY_ABSENT);
        service.applyDecline("req-1", false, "dean");
        assertThat(request.getReRequestable()).isFalse();
    }

    @Test
    void failedBlockIsNotClaimedAsPermanent() {
        // Access unreachable — the user is NOT excluded, so the request must not
        // be recorded as permanently declined (that would be a silent lie).
        when(entitlements.revokeAccess(request)).thenReturn(RevokeOutcome.UNREACHABLE);

        String note = service.applyDecline("req-1", false, "dean");

        assertThat(request.getReRequestable()).isTrue();
        assertThat(note).contains("could NOT be applied");
        verify(audit).record(eq("access-block-failed"), eq("req-1"), anyString(), anyString(), eq("dean"));
    }

    @Test
    void liftingAPermanentDeclineRestoresAccessAndNotifies() {
        request.setReRequestable(false);
        request.setScimUserId("scim-dean");
        when(entitlements.restoreAccess(request)).thenReturn(RevokeOutcome.REVOKED);

        RevokeOutcome outcome = service.allowReRequest(request, "dean");

        assertThat(outcome).isEqualTo(RevokeOutcome.REVOKED);
        assertThat(request.getReRequestable()).isTrue();
        assertThat(request.getRestoredAt()).isNotNull();
        verify(audit).record(eq("access-reopened"), eq("req-1"), anyString(), anyString(), eq("dean"));
        verify(webhooks).notifyReopened(request);
    }

    @Test
    void failedLiftLeavesTheBlockInPlace() {
        request.setReRequestable(false);
        when(entitlements.restoreAccess(request)).thenReturn(RevokeOutcome.UNREACHABLE);

        service.allowReRequest(request, "dean");

        assertThat(request.getReRequestable()).isFalse();
        verify(webhooks, never()).notifyReopened(any());
    }
}
