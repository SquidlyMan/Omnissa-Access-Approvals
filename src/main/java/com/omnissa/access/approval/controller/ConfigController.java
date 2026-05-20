package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.RestPreconditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping(value = Mappings.CONFIG)
public class ConfigController {

    @Autowired
    private OmnissaServerRepository repository;

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
