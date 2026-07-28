package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.config.AdminOAuthEnvironmentPostProcessor;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.service.TenantStatusService;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.RestPreconditions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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

    @Autowired
    private TenantStatusService tenantStatusService;

    @Value("${omnissa.auth.local-login-disabled:false}")
    private boolean localLoginDisabled;

    /**
     * Present only when an admin OIDC client is configured — see
     * {@link AdminOAuthEnvironmentPostProcessor}.
     */
    @Autowired
    private ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    /**
     * Public (unauthenticated) auth-mode discovery so the login page knows
     * which sign-in options to render.
     *
     * <p>{@code oauthEnabled} asks the registration itself rather than reading
     * {@code omnissa.admin-oauth.client-id}, so the button is offered exactly
     * when the security chain can service it. A client id set without the
     * tenant's OIDC endpoints yields no registration, and would otherwise
     * render a "Sign in with Omnissa Access" button that lands on a 404.
     */
    @GetMapping("/auth")
    public ResponseEntity<?> getAuthConfig() {
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        boolean oauthEnabled = registrations != null && registrations.findByRegistrationId(
                AdminOAuthEnvironmentPostProcessor.REGISTRATION_ID) != null;
        return ResponseEntity.ok(Map.of(
                "localLoginDisabled", localLoginDisabled,
                "oauthEnabled", oauthEnabled
        ));
    }

    /**
     * Omnissa Access connectivity status (authenticated). Backed by
     * {@link TenantStatusService} so the dashboard tile and the health
     * endpoints share one probe and one 60s cache rather than each polling the
     * tenant independently.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getConnectivityStatus() {
        TenantStatusService.TenantStatus tenant = tenantStatusService.current();
        String version = ConfigController.class.getPackage().getImplementationVersion();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("version", version != null ? version : "dev");
        status.put("tenantUrl", tenant.tenantUrl());
        status.put("reachable", tenant.reachable());
        status.put("checkedAt", tenant.checkedAt());
        status.put("error", tenant.error());
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
