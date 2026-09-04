package com.omnissa.access.approval.update;

import java.util.Date;
import java.util.List;

/** The full picture for the console: what was detected, what has been asked for, and the floor. */
public record UpdateView(
        UpdateSnapshot detection,
        String pendingTarget,
        Date pendingSince,
        boolean controlDirectoryMounted,
        String rollbackFloor,
        List<String> knownVersions) {

    public static UpdateView of(UpdateSnapshot detection, UpdateApprovalService approvals) {
        UpdateApprovalService.Approval pending = approvals.pending().orElse(null);
        return new UpdateView(
                detection,
                pending != null ? pending.target() : null,
                pending != null ? pending.requestedAt() : null,
                approvals.controlDirectoryMounted(),
                RollbackFloor.FLOOR.toString(),
                detection.knownVersions());
    }
}
