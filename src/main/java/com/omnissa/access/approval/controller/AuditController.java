package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.repository.AuditEventRepository;
import com.omnissa.access.approval.util.Csv;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.Date;

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

    /**
     * Exports the audit trail.
     *
     * <p>Distinct from {@code /api/approvals/export.csv}, which exports the
     * request table — a different dataset. Until this existed, the audit tab's
     * export button downloaded requests, so the audit trail itself could only
     * be read a page at a time on screen: the one thing the auditor role exists
     * to do had no export.
     *
     * <p>Restricted to admins and auditors in {@code SecurityConfig}. A bulk
     * export is not a read but an extraction — it produces a file that leaves
     * the application's controls entirely — so it is granted to the two roles
     * with an explicit records mandate rather than to everyone who may read the
     * trail on screen.
     */
    @GetMapping("/export.csv")
    public ResponseEntity<String> exportCsv() {
        StringBuilder csv = new StringBuilder(
                "timestamp,actor,action,requestId,resourceName,requesterId,requesterName,requesterEmail,message\n");
        for (AuditEvent event : auditEventRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))) {
            csv.append(Csv.field(event.getTimestamp() == null
                            ? null : event.getTimestamp().toInstant().toString())).append(',')
               .append(Csv.field(event.getAdminUsername())).append(',')
               .append(Csv.field(event.getAction())).append(',')
               .append(Csv.field(event.getRequestId())).append(',')
               .append(Csv.field(event.getResourceName())).append(',')
               .append(Csv.field(event.getRequesterId())).append(',')
               .append(Csv.field(event.getRequesterName())).append(',')
               .append(Csv.field(event.getRequesterEmail())).append(',')
               .append(Csv.field(event.getMessage())).append('\n');
        }

        String filename = "audit-trail-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return new ResponseEntity<>(csv.toString(), headers, HttpStatus.OK);
    }
}
