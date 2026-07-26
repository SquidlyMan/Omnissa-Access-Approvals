package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.service.DependencyHealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dependency health, split by what the caller is trusted to see.
 *
 * <p>Uptime Kuma cannot express a warning: a monitor is up or down. So warning
 * versus outage comes from <em>which</em> monitor fires — one watching
 * {@code /actuator/health} for liveness, another keyword-matching the public
 * endpoint here for dependencies — each routed to its own notification.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Autowired
    private DependencyHealthService dependencyHealth;

    /**
     * Aggregate status word only, unauthenticated.
     *
     * <p>Carries no tenant hostname, no error strings and no counts. A monitor
     * needs one token to match on; anything more would publish the shape of the
     * deployment — and a drift count in particular would leak request volume to
     * anyone who can reach the URL.
     *
     * <p>Always HTTP 200, including when DEGRADED, so a plain HTTP monitor stays
     * green and only the keyword monitor trips.
     */
    @GetMapping("/deps")
    public ResponseEntity<Map<String, String>> aggregate() {
        return ResponseEntity.ok(Map.of("status", dependencyHealth.aggregateStatus()));
    }

    /** Full per-component detail. Authenticated — this is where the specifics live. */
    @GetMapping("/dependencies")
    public ResponseEntity<Map<String, Object>> dependencies() {
        return ResponseEntity.ok(dependencyHealth.detail());
    }
}
