package com.omnissa.access.approval.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate health of the things this tool depends on.
 *
 * <p>Deliberately separate from {@code /actuator/health}, which must keep
 * meaning "the container is alive" and nothing more: it is consumed by the
 * Docker health check, {@code deploy.sh}, CasaOS's tile probe and the UAG. A
 * dependency fault must never reach it — CasaOS <em>recreates the container</em>
 * when its probe fails and the UAG de-pools the backend, so letting an Omnissa
 * Access outage turn that endpoint red would take down a perfectly healthy
 * service in response to someone else's problem.
 *
 * <p>Hence two states rather than one: DOWN means this tool is broken, DEGRADED
 * means something it relies on is. Only the second is expected to be common.
 */
@Service
public class DependencyHealthService {

    public static final String UP = "UP";
    public static final String DEGRADED = "DEGRADED";
    public static final String DOWN = "DOWN";

    @Autowired
    private TenantStatusService tenantStatus;

    @Autowired
    private SchedulerHeartbeat schedulerHeartbeat;

    @Autowired
    private NotificationHealth notificationHealth;

    @Autowired
    private ApprovalDriftService approvalDrift;

    @Value("${webhook.url:}")
    private String webhookUrl;

    /** Aggregate only — safe to expose unauthenticated. */
    public String aggregateStatus() {
        String worst = UP;
        for (String status : componentStatuses().values()) {
            worst = worse(worst, status);
        }
        return worst;
    }

    /** Full per-component breakdown. Names the tenant and error strings, so authenticated only. */
    public Map<String, Object> detail() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", aggregateStatus());

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, String> statuses = componentStatuses();

        TenantStatusService.TenantStatus tenant = tenantStatus.current();
        components.put("omnissaAccess", component(statuses.get("omnissaAccess"), Map.of(
                "configured", tenant.configured(),
                "reachable", tenant.reachable(),
                "tenantUrl", tenant.tenantUrl() == null ? "" : tenant.tenantUrl(),
                "checkedAt", tenant.checkedAt(),
                "error", tenant.error() == null ? "" : tenant.error())));

        components.put("scheduler", component(statuses.get("scheduler"), schedulerHeartbeat.detail()));
        components.put("approvalDrift", component(statuses.get("approvalDrift"), approvalDrift.detail()));

        if (statuses.containsKey("notifications")) {
            components.put("notifications",
                    component(statuses.get("notifications"), notificationHealth.detail()));
        }

        body.put("components", components);
        return body;
    }

    private Map<String, String> componentStatuses() {
        Map<String, String> statuses = new LinkedHashMap<>();

        TenantStatusService.TenantStatus tenant = tenantStatus.current();
        // Not-yet-configured is a setup state, not a fault — a fresh install
        // should not page anyone.
        statuses.put("omnissaAccess",
                !tenant.configured() ? UP : (tenant.reachable() ? UP : DEGRADED));

        // The one failure with no other outward symptom: if the sweeps stop,
        // time-bound access silently never expires.
        statuses.put("scheduler", schedulerHeartbeat.anyStale() ? DEGRADED : UP);

        ApprovalDriftService.Drift drift = approvalDrift.current();
        // Unknown drift is not reported as a fault of its own — the tenant being
        // unreachable is already covered above, and double-counting it would
        // make one outage look like two.
        statuses.put("approvalDrift", drift.hasDrift() ? DEGRADED : UP);

        if (webhookUrl != null && !webhookUrl.isBlank() && notificationHealth.hasActivity()) {
            statuses.put("notifications", notificationHealth.isDegraded() ? DEGRADED : UP);
        }
        return statuses;
    }

    private static Map<String, Object> component(String status, Map<String, Object> detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", status);
        entry.putAll(detail);
        return entry;
    }

    private static String worse(String a, String b) {
        if (DOWN.equals(a) || DOWN.equals(b)) {
            return DOWN;
        }
        if (DEGRADED.equals(a) || DEGRADED.equals(b)) {
            return DEGRADED;
        }
        return UP;
    }
}
