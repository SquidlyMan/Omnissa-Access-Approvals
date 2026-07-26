package com.omnissa.access.approval.service;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Aggregate dependency health (#44).
 *
 * <p>The load-bearing property is that a dependency fault produces DEGRADED and
 * never DOWN. {@code /actuator/health} is consumed by the Docker health check,
 * CasaOS's tile probe and the UAG — CasaOS <em>recreates the container</em> when
 * that probe fails — so letting someone else's outage turn it red would take
 * down a healthy service.
 */
class DependencyHealthTest {

    private DependencyHealthService health;
    private TenantStatusService tenant;
    private SchedulerHeartbeat heartbeat;
    private NotificationHealth notifications;
    private ApprovalDriftService drift;

    private CalloutRequest pending(String requestId) {
        CalloutRequest r = new CalloutRequest(CalloutOperation.activation, requestId, "app-uuid",
                "I Am Showcase (Access)", "751802", null, null, null, null, null, null);
        r.setState("pending");
        return r;
    }

    @BeforeEach
    void setUp() {
        health = new DependencyHealthService();
        tenant = mock(TenantStatusService.class);
        heartbeat = new SchedulerHeartbeat();
        notifications = new NotificationHealth();
        drift = mock(ApprovalDriftService.class);

        ReflectionTestUtils.setField(health, "tenantStatus", tenant);
        ReflectionTestUtils.setField(health, "schedulerHeartbeat", heartbeat);
        ReflectionTestUtils.setField(health, "notificationHealth", notifications);
        ReflectionTestUtils.setField(health, "approvalDrift", drift);
        ReflectionTestUtils.setField(health, "webhookUrl", "");

        reachable();
        noDrift();
    }

    private void reachable() {
        when(tenant.current()).thenReturn(new TenantStatusService.TenantStatus(
                true, true, "dean.us1.wss.workspaceone.com", null, Instant.now().toString()));
    }

    private void noDrift() {
        when(drift.current()).thenReturn(
                new ApprovalDriftService.Drift(0, 0, 0, List.of(), null, Instant.now().toString()));
    }

    @Test
    void healthyWhenEverythingIsFine() {
        assertEquals(DependencyHealthService.UP, health.aggregateStatus());
    }

    @Test
    void anUnreachableTenantDegradesButNeverGoesDown() {
        when(tenant.current()).thenReturn(new TenantStatusService.TenantStatus(
                true, false, "dean.us1.wss.workspaceone.com", "Connection timed out",
                Instant.now().toString()));

        assertEquals(DependencyHealthService.DEGRADED, health.aggregateStatus(),
                "a third-party outage must not read as this service being down");
    }

    /** A fresh install has no tenant yet; that is setup, not a fault to page on. */
    @Test
    void notYetConfiguredIsNotAFault() {
        when(tenant.current()).thenReturn(new TenantStatusService.TenantStatus(
                false, false, "", "Not configured", Instant.now().toString()));

        assertEquals(DependencyHealthService.UP, health.aggregateStatus());
    }

    @Test
    void aWedgedSchedulerDegrades() {
        @SuppressWarnings("unchecked")
        Map<String, Instant> lastRun =
                (Map<String, Instant>) ReflectionTestUtils.getField(heartbeat, "lastRun");
        lastRun.put(SchedulerHeartbeat.JIT_EXPIRY, Instant.now().minus(Duration.ofMinutes(30)));

        assertEquals(DependencyHealthService.DEGRADED, health.aggregateStatus());
    }

    @Test
    void driftDegrades() {
        when(drift.current()).thenReturn(new ApprovalDriftService.Drift(
                5, 0, 5, List.of("I Am Showcase (PingFed)"), null, Instant.now().toString()));

        assertEquals(DependencyHealthService.DEGRADED, health.aggregateStatus());
    }

    /**
     * When the tenant is unreachable the drift check cannot run. That must not be
     * reported as a second fault — one outage should not look like two.
     */
    @Test
    void unknownDriftIsNotCountedAsItsOwnFault() {
        when(drift.current()).thenReturn(new ApprovalDriftService.Drift(
                -1, -1, 0, List.of(), "Connection timed out", Instant.now().toString()));

        assertEquals(DependencyHealthService.UP, health.aggregateStatus(),
                "unknown drift alone is not a fault; the tenant component reports the outage");
    }

    @Test
    void notificationsAreOmittedUntilSomethingHasBeenSent() {
        ReflectionTestUtils.setField(health, "webhookUrl", "https://hooks.slack.com/services/T/B/X");

        assertFalse(health.detail().toString().contains("notifications"),
                "nothing has been attempted, so there is nothing to report");

        notifications.recordFailure("500 Server Error");
        notifications.recordFailure("500 Server Error");
        notifications.recordFailure("500 Server Error");

        assertEquals(DependencyHealthService.DEGRADED, health.aggregateStatus());
        assertTrue(health.detail().toString().contains("notifications"));
    }

    @Test
    void aSuccessClearsTheFailureRun() {
        notifications.recordFailure("boom");
        notifications.recordFailure("boom");
        notifications.recordFailure("boom");
        assertTrue(notifications.isDegraded());

        notifications.recordSuccess();
        assertFalse(notifications.isDegraded());
    }

    @Test
    void driftComparesAccessAgainstPendingRecordsOnly() {
        ApprovalsInterface approvals = mock(ApprovalsInterface.class);
        ApprovalsRepository repository = mock(ApprovalsRepository.class);
        ApprovalDriftService service = new ApprovalDriftService();
        ReflectionTestUtils.setField(service, "approvalsInterface", approvals);
        ReflectionTestUtils.setField(service, "approvalsRepository", repository);

        when(approvals.getPendingApprovals())
                .thenReturn(new CalloutRequest[]{pending("known"), pending("orphaned")});
        CalloutRequest decided = pending("decided");
        decided.setState("approved");
        when(repository.findAll()).thenReturn(List.of(pending("known"), decided));

        ApprovalDriftService.Drift result = service.current();

        assertEquals(2, result.accessPending());
        assertEquals(1, result.queuePending());
        assertEquals(1, result.missingLocally(), "the orphaned request is the actionable one");
        assertTrue(result.hasDrift());
    }
}
