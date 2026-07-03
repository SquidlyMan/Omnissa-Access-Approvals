package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.RestPreconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

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
