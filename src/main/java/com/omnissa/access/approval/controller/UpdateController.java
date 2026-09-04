package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.update.UpdateApprovalService;
import com.omnissa.access.approval.update.UpdateCheckService;
import com.omnissa.access.approval.update.UpdateView;
import com.omnissa.access.approval.util.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Update detection and approval for the admin console (#83). Authorization
 * lives in {@code SecurityConfig}: status is readable by any signed-in role;
 * checking and approving are an administrator's acts.
 */
@RestController
@RequestMapping(Mappings.UPDATES)
public class UpdateController {

    private final UpdateCheckService checks;
    private final UpdateApprovalService approvals;
    private final AuditService audit;

    public UpdateController(UpdateCheckService checks, UpdateApprovalService approvals, AuditService audit) {
        this.checks = checks;
        this.approvals = approvals;
        this.audit = audit;
    }

    /** Last-known state; never touches the registry, so it is safe on every page load. */
    @GetMapping("/status")
    public ResponseEntity<UpdateView> status() {
        return ResponseEntity.ok(view(checks.current()));
    }

    /** "Check now". Synchronous, bounded by the registry client's timeouts. */
    @PostMapping("/check")
    public ResponseEntity<UpdateView> check() {
        return ResponseEntity.ok(view(checks.check()));
    }

    public record ApproveRequest(String target, String confirmation) {
    }

    /**
     * Hand a version to the host-side updater. Refusals come back as 400 with
     * a message the administrator can act on; a target below the rollback
     * floor comes back as 409 naming what it would reopen, until the version
     * is typed again to confirm.
     */
    @PostMapping("/approve")
    public ResponseEntity<?> approve(@RequestBody ApproveRequest body) {
        try {
            UpdateApprovalService.Approval approval =
                    approvals.approve(body.target(), body.confirmation(), audit.currentAdmin());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("approved", approval);
            response.put("view", view(checks.current()));
            return ResponseEntity.ok(response);
        } catch (UpdateApprovalService.Refused refused) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("error", refused.getMessage());
            response.put("confirmationRequired", refused.confirmationRequired());
            response.put("reopened", refused.reopened());
            return ResponseEntity.status(refused.confirmationRequired() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                    .body(response);
        } catch (UpdateApprovalService.NotDeployable nd) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", nd.getMessage()));
        }
    }

    private UpdateView view(com.omnissa.access.approval.update.UpdateSnapshot snapshot) {
        return UpdateView.of(snapshot, approvals);
    }
}
