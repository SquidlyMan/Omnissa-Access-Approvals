package com.omnissa.access.approval.interfaces;

import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.RestPreconditions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ApprovalsInterfaceImpl#requestResponse} decision-delivery outcomes.
 * The internally-constructed {@link OmnissaRestClient} is intercepted with
 * {@code mockConstruction} and the static {@link RestPreconditions} lookups are stubbed.
 */
class DecisionOutcomeTest {

    private static CalloutRequest pendingRequest() {
        CalloutRequest req = new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                "Salesforce", "jdoe", null, null, null, null, null, null);
        req.setState("pending");
        return req;
    }

    private ApprovalsInterfaceImpl newImpl(ApprovalsRepository repository, AuditService auditService) {
        ApprovalsInterfaceImpl impl = new ApprovalsInterfaceImpl();
        ReflectionTestUtils.setField(impl, "repository", repository);
        ReflectionTestUtils.setField(impl, "auditService", auditService);
        return impl;
    }

    @Test
    void success2xxReturnsDeliveredAndSetsApprovedState() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        AuditService auditService = mock(AuditService.class);
        CalloutRequest request = pendingRequest();
        when(repository.findByRequestId("req-1")).thenReturn(request);
        when(auditService.currentAdmin()).thenReturn("alice");

        try (MockedStatic<RestPreconditions> pre = mockStatic(RestPreconditions.class);
             MockedConstruction<OmnissaRestClient> ignored = mockConstruction(OmnissaRestClient.class)) {
            pre.when(RestPreconditions::omnissaServerConfig).thenReturn(mock(OmnissaServer.class));
            pre.when(RestPreconditions::omnissaServerBaseUrl).thenReturn("https://access.example.com");

            DecisionOutcome outcome = newImpl(repository, auditService)
                    .requestResponse(new CalloutResponse("req-1", true, "looks good"));

            assertThat(outcome).isEqualTo(DecisionOutcome.DELIVERED);
        }

        assertThat(request.getState()).isEqualTo("approved");
        assertThat(request.getResponseMessage()).isEqualTo("looks good");
        assertThat(request.getDecidedBy()).isEqualTo("alice");
        verify(repository).save(request);
    }

    @Test
    void rejectionSuccessSetsRejectedState() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        AuditService auditService = mock(AuditService.class);
        CalloutRequest request = pendingRequest();
        when(repository.findByRequestId("req-1")).thenReturn(request);

        try (MockedStatic<RestPreconditions> pre = mockStatic(RestPreconditions.class);
             MockedConstruction<OmnissaRestClient> ignored = mockConstruction(OmnissaRestClient.class)) {
            pre.when(RestPreconditions::omnissaServerConfig).thenReturn(mock(OmnissaServer.class));
            pre.when(RestPreconditions::omnissaServerBaseUrl).thenReturn("https://access.example.com");

            DecisionOutcome outcome = newImpl(repository, auditService)
                    .requestResponse(new CalloutResponse("req-1", false, null));

            assertThat(outcome).isEqualTo(DecisionOutcome.DELIVERED);
        }
        assertThat(request.getState()).isEqualTo("rejected");
    }

    @Test
    void clientError4xxReturnsExpiredAndMarksRequestExpired() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        AuditService auditService = mock(AuditService.class);
        CalloutRequest request = pendingRequest();
        when(repository.findByRequestId("req-1")).thenReturn(request);
        when(auditService.currentAdmin()).thenReturn("bob");

        try (MockedStatic<RestPreconditions> pre = mockStatic(RestPreconditions.class);
             MockedConstruction<OmnissaRestClient> mc = mockConstruction(OmnissaRestClient.class,
                     (m, ctx) -> when(m.exchange(any(), any(), any(), any()))
                             .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND)))) {
            pre.when(RestPreconditions::omnissaServerConfig).thenReturn(mock(OmnissaServer.class));
            pre.when(RestPreconditions::omnissaServerBaseUrl).thenReturn("https://access.example.com");

            DecisionOutcome outcome = newImpl(repository, auditService)
                    .requestResponse(new CalloutResponse("req-1", true, "approve me"));

            assertThat(outcome).isEqualTo(DecisionOutcome.EXPIRED);
        }

        assertThat(request.getState()).isEqualTo("expired");
        assertThat(request.getResponseMessage()).contains("no longer exists");
        assertThat(request.getDecidedBy()).isEqualTo("bob");
        verify(repository).save(request);
    }

    @Test
    void unreachableReturnsUnreachableAndLeavesStateUnchanged() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        AuditService auditService = mock(AuditService.class);
        CalloutRequest request = pendingRequest();
        when(repository.findByRequestId("req-1")).thenReturn(request);

        try (MockedStatic<RestPreconditions> pre = mockStatic(RestPreconditions.class);
             MockedConstruction<OmnissaRestClient> mc = mockConstruction(OmnissaRestClient.class,
                     (m, ctx) -> when(m.exchange(any(), any(), any(), any()))
                             .thenThrow(new ResourceAccessException("connection refused")))) {
            pre.when(RestPreconditions::omnissaServerConfig).thenReturn(mock(OmnissaServer.class));
            pre.when(RestPreconditions::omnissaServerBaseUrl).thenReturn("https://access.example.com");

            DecisionOutcome outcome = newImpl(repository, auditService)
                    .requestResponse(new CalloutResponse("req-1", true, "approve me"));

            assertThat(outcome).isEqualTo(DecisionOutcome.UNREACHABLE);
        }

        // State untouched so the decision can be retried later.
        assertThat(request.getState()).isEqualTo("pending");
        assertThat(request.getResponseMessage()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void unknownRequestIdStillReturnsDeliveredWithoutSaving() {
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        AuditService auditService = mock(AuditService.class);
        when(repository.findByRequestId("missing")).thenReturn(null);

        try (MockedStatic<RestPreconditions> pre = mockStatic(RestPreconditions.class);
             MockedConstruction<OmnissaRestClient> ignored = mockConstruction(OmnissaRestClient.class)) {
            pre.when(RestPreconditions::omnissaServerConfig).thenReturn(mock(OmnissaServer.class));
            pre.when(RestPreconditions::omnissaServerBaseUrl).thenReturn("https://access.example.com");

            DecisionOutcome outcome = newImpl(repository, auditService)
                    .requestResponse(new CalloutResponse("missing", true, null));

            assertThat(outcome).isEqualTo(DecisionOutcome.DELIVERED);
        }
        verify(repository, never()).save(any());
    }
}
