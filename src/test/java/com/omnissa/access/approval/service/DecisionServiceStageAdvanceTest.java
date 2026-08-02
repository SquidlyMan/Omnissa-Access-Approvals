package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.ApprovalStage;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chained (#53) approval that isn't the chain's final stage must never
 * reach Access — it advances {@code currentStage} locally instead. A reject
 * at any stage, and an approval of the final stage, must fall straight
 * through to the ordinary delivery path unchanged.
 */
class DecisionServiceStageAdvanceTest {

    private static CalloutRequest pendingChainedRequest(int currentStage) {
        CalloutRequest request = new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                "Salesforce", "jdoe", null, null, null, null, null, null);
        request.setState("pending");
        request.setChainId(1L);
        request.setCurrentStage(currentStage);
        return request;
    }

    private static ApprovalStage stage(int order) {
        ApprovalStage stage = new ApprovalStage();
        stage.setChainId(1L);
        stage.setStageOrder(order);
        stage.setApproverType("ROLE");
        stage.setApproverValue("ROLE_APPROVER");
        return stage;
    }

    private DecisionService newService(ApprovalsRepository repository, ApprovalChainService chainService,
                                       ApprovalsInterface approvalsInterface, AuditService auditService) {
        DecisionService service = new DecisionService();
        ReflectionTestUtils.setField(service, "approvalsRepository", repository);
        ReflectionTestUtils.setField(service, "approvalChainService", chainService);
        ReflectionTestUtils.setField(service, "approvalsInterface", approvalsInterface);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "sseController", mock(SseController.class));
        return service;
    }

    @Test
    void approvingANonFinalStageAdvancesWithoutContactingAccess() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        ApprovalChainService chainService = mock(ApprovalChainService.class);
        ApprovalsInterface approvalsInterface = mock(ApprovalsInterface.class);
        AuditService auditService = mock(AuditService.class);

        CalloutRequest request = pendingChainedRequest(1);
        when(repository.findByRequestId("req-1")).thenReturn(request);
        when(chainService.stagesFor(1L)).thenReturn(List.of(stage(1), stage(2), stage(3)));

        DecisionOutcome outcome = newService(repository, chainService, approvalsInterface, auditService)
                .decide("req-1", true, "looks fine", null, null, "alice");

        assertThat(outcome).isEqualTo(DecisionOutcome.STAGE_ADVANCED);
        assertThat(request.getCurrentStage()).isEqualTo(2);
        assertThat(request.getState())
                .as("state stays pending through a chain so it remains visible in the existing queue view")
                .isEqualTo("pending");
        verify(approvalsInterface, never()).requestResponse(any());
        verify(repository).save(request);
    }

    @Test
    void approvingTheFinalStageDeliversToAccessNormally() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        ApprovalChainService chainService = mock(ApprovalChainService.class);
        ApprovalsInterface approvalsInterface = mock(ApprovalsInterface.class);
        AuditService auditService = mock(AuditService.class);

        CalloutRequest request = pendingChainedRequest(3);
        when(repository.findByRequestId("req-1")).thenReturn(request);
        when(chainService.stagesFor(1L)).thenReturn(List.of(stage(1), stage(2), stage(3)));
        when(approvalsInterface.requestResponse(any())).thenReturn(DecisionOutcome.UNREACHABLE);

        DecisionOutcome outcome = newService(repository, chainService, approvalsInterface, auditService)
                .decide("req-1", true, "final sign-off", null, null, "carol");

        assertThat(outcome).isEqualTo(DecisionOutcome.UNREACHABLE);
        assertThat(request.getCurrentStage())
                .as("the final stage's approval must not advance past the last stage")
                .isEqualTo(3);
        verify(approvalsInterface).requestResponse(any());
    }

    @Test
    void rejectingAnyStageDeliversToAccessImmediatelyRatherThanAdvancing() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        ApprovalChainService chainService = mock(ApprovalChainService.class);
        ApprovalsInterface approvalsInterface = mock(ApprovalsInterface.class);
        AuditService auditService = mock(AuditService.class);

        CalloutRequest request = pendingChainedRequest(1);
        when(repository.findByRequestId("req-1")).thenReturn(request);
        when(approvalsInterface.requestResponse(any())).thenReturn(DecisionOutcome.UNREACHABLE);

        DecisionOutcome outcome = newService(repository, chainService, approvalsInterface, auditService)
                .decide("req-1", false, "no", null, null, "dave");

        assertThat(outcome).isEqualTo(DecisionOutcome.UNREACHABLE);
        verify(approvalsInterface).requestResponse(any());
        verify(chainService, never()).stagesFor(any());
    }

    @Test
    void aNonChainedRequestIsUnaffected() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        ApprovalChainService chainService = mock(ApprovalChainService.class);
        ApprovalsInterface approvalsInterface = mock(ApprovalsInterface.class);
        AuditService auditService = mock(AuditService.class);

        CalloutRequest request = new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                "Salesforce", "jdoe", null, null, null, null, null, null);
        request.setState("pending"); // chainId left null
        when(repository.findByRequestId("req-1")).thenReturn(request);
        // UNREACHABLE, not DELIVERED, so this stays a unit test of the chain
        // branch and doesn't also need entitlementsInterface/webhookNotifier/
        // mailNotification mocked — those are exercised by DecisionOutcomeTest.
        when(approvalsInterface.requestResponse(any())).thenReturn(DecisionOutcome.UNREACHABLE);

        DecisionOutcome outcome = newService(repository, chainService, approvalsInterface, auditService)
                .decide("req-1", true, "ok", null, null, "eve");

        assertThat(outcome).isEqualTo(DecisionOutcome.UNREACHABLE);
        verify(chainService, never()).stagesFor(any());
    }
}
