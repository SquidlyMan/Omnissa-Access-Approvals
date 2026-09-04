package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.update.UpdateCheckService;
import com.omnissa.access.approval.update.UpdateSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Update detection for the admin console. Authorization lives in
 * {@code SecurityConfig}: status is readable by any signed-in role, running a
 * check is an administrator's act.
 */
@RestController
@RequestMapping(Mappings.UPDATES)
public class UpdateController {

    private final UpdateCheckService service;

    public UpdateController(UpdateCheckService service) {
        this.service = service;
    }

    /** Last-known state; never touches the registry, so it is safe on every page load. */
    @GetMapping("/status")
    public ResponseEntity<UpdateSnapshot> status() {
        return ResponseEntity.ok(service.current());
    }

    /** "Check now". Synchronous, bounded by the registry client's timeouts. */
    @PostMapping("/check")
    public ResponseEntity<UpdateSnapshot> check() {
        return ResponseEntity.ok(service.check());
    }
}
