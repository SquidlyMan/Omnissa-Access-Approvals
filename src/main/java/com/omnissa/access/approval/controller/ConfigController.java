package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.RestPreconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(value = Mappings.CONFIG)
public class ConfigController {

    @Autowired
    private OmnissaServerRepository repository;

    @Value("${omnissa.auth.local-login-disabled:false}")
    private boolean localLoginDisabled;

    @Value("${omnissa.admin-oauth.client-id:}")
    private String adminOauthClientId;

    /**
     * Public (unauthenticated) auth-mode discovery so the login page knows
     * which sign-in options to render.
     */
    @GetMapping("/auth")
    public ResponseEntity<?> getAuthConfig() {
        return ResponseEntity.ok(Map.of(
                "localLoginDisabled", localLoginDisabled,
                "oauthEnabled", !adminOauthClientId.isBlank()
        ));
    }

    // Connectivity check cache — dashboard polling must not hammer the tenant.
    private static final long STATUS_CACHE_MILLIS = 60_000L;
    private volatile Map<String, Object> statusCache;
    private volatile long statusCheckedAt;

    /**
     * Omnissa Access connectivity status (authenticated). Attempts a
     * client_credentials token fetch against the configured tenant; the
     * result is cached in-memory for 60 seconds.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getConnectivityStatus() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = statusCache;
        if (cached != null && now - statusCheckedAt < STATUS_CACHE_MILLIS) {
            return ResponseEntity.ok(cached);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("tenantUrl", "");
        status.put("reachable", false);
        status.put("checkedAt", Instant.now().toString());
        status.put("error", null);
        try {
            if (repository.count() == 0) {
                status.put("error", "Not configured");
            } else {
                OmnissaServer server = repository.findAll().get(0);
                status.put("tenantUrl", server.getUrl() != null ? server.getUrl() : "");
                new OmnissaRestClient(server).checkToken();
                status.put("reachable", true);
            }
        } catch (Exception e) {
            status.put("reachable", false);
            status.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        statusCache = status;
        statusCheckedAt = now;
        return ResponseEntity.ok(status);
    }

    @GetMapping("/server")
    public ResponseEntity<?> getServerEntry() {
        RestPreconditions.checkConfigAvailability();
        return ResponseEntity.ok(RestPreconditions.omnissaServerConfig());
    }

    /**
     * Save the Omnissa Access SaaS tenant configuration.
     * Only one configuration is allowed at a time.
     */
    @PostMapping("/server")
    public ResponseEntity<?> createServerEntry(@RequestBody @Valid OmnissaServer server) {
        RestPreconditions.checkIfConfigExists();
        repository.save(server);
        return ResponseEntity.ok(server);
    }

    /**
     * Update the existing Omnissa Access SaaS tenant configuration.
     */
    @PutMapping("/server")
    public ResponseEntity<?> updateServerEntry(@RequestBody @Valid OmnissaServer server) {
        RestPreconditions.checkConfigAvailability();
        OmnissaServer existing = RestPreconditions.omnissaServerConfig();
        server.setId(existing.getId());
        repository.save(server);
        return ResponseEntity.ok(server);
    }

    @DeleteMapping("/server")
    public ResponseEntity<?> deleteServerEntry() {
        RestPreconditions.checkConfigAvailability();
        repository.delete(RestPreconditions.omnissaServerConfig());
        return ResponseEntity.ok(null);
    }
}
