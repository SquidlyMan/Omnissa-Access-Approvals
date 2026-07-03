package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit-trail API for the admin UI. Authenticated by default —
 * the endpoint is intentionally NOT in the security permitAll list.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    @Autowired
    AuditEventRepository auditEventRepository;

    @GetMapping
    public ResponseEntity<Page<AuditEvent>> getAuditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? Math.min(size, 500) : 25;
        return ResponseEntity.ok(
                auditEventRepository.findAllByOrderByIdDesc(PageRequest.of(safePage, safeSize)));
    }
}
