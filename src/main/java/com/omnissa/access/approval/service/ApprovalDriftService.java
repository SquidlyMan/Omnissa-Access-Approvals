package com.omnissa.access.approval.service;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Requests Omnissa Access is waiting on that this tool does not know about.
 *
 * <p>Access holds an approval open until it receives a decision. If the local
 * record is missing, no decision can ever be sent: the requester waits forever,
 * the app never provisions, and nothing in the tool indicates why. This happened
 * in practice — five requests, one of them the administrator's own, sat stuck
 * for days and presented as an Access provisioning fault.
 *
 * <p>Deletion of a pending record is only one cause, and it is now prevented.
 * The commoner one is a callout that never arrived: a reverse-proxy
 * misconfiguration, an outage, or rate limiting. Those leave no trace at all in
 * the tool, so drift is the only way they surface.
 *
 * <p>Cached, because the check costs a call to the tenant and is read by an
 * external monitor on whatever interval it likes.
 */
@Service
public class ApprovalDriftService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalDriftService.class);
    private static final long CACHE_MILLIS = 60_000L;

    @Autowired
    private ApprovalsInterface approvalsInterface;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    private volatile Drift cached;
    private volatile long checkedAt;

    /**
     * @param missingLocally requests Access is waiting on that the queue has no
     *                       pending record for — the actionable number
     * @param error          set when the tenant could not be reached; drift is
     *                       then unknown rather than zero
     */
    public record Drift(int accessPending, int queuePending, int missingLocally,
                        List<String> sampleResources, String error, String checkedAt) {

        public boolean hasDrift() {
            return missingLocally > 0;
        }

        /** Unknown is not the same as healthy; the caller must not read it as zero. */
        public boolean isUnknown() {
            return error != null;
        }
    }

    public Drift current() {
        long now = System.currentTimeMillis();
        Drift snapshot = cached;
        if (snapshot != null && now - checkedAt < CACHE_MILLIS) {
            return snapshot;
        }
        Drift fresh = probe();
        cached = fresh;
        checkedAt = now;
        return fresh;
    }

    private Drift probe() {
        String stamp = Instant.now().toString();
        try {
            CalloutRequest[] remote = approvalsInterface.getPendingApprovals();
            List<CalloutRequest> accessPending = remote == null ? List.of() : Arrays.asList(remote);

            Set<String> knownPending = approvalsRepository.findAll().stream()
                    .filter(r -> "pending".equalsIgnoreCase(r.getState()))
                    .map(CalloutRequest::getRequestId)
                    .collect(Collectors.toSet());

            List<CalloutRequest> missing = accessPending.stream()
                    .filter(r -> r.getRequestId() != null && !knownPending.contains(r.getRequestId()))
                    .toList();

            List<String> sample = missing.stream()
                    .map(CalloutRequest::getResourceName)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .limit(5)
                    .toList();

            if (!missing.isEmpty()) {
                logger.warn("Approval drift: Omnissa Access is waiting on {} request(s) with no pending "
                        + "record here. Use Pull from Access to recover them. Apps: {}",
                        missing.size(), sample);
            }
            return new Drift(accessPending.size(), knownPending.size(), missing.size(), sample, null, stamp);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.debug("Approval drift check failed: {}", message);
            return new Drift(-1, -1, 0, List.of(), message, stamp);
        }
    }

    public Map<String, Object> detail() {
        Drift drift = current();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("accessPending", drift.accessPending() < 0 ? null : drift.accessPending());
        detail.put("queuePending", drift.queuePending() < 0 ? null : drift.queuePending());
        detail.put("missingLocally", drift.missingLocally());
        detail.put("apps", drift.sampleResources());
        detail.put("checkedAt", drift.checkedAt());
        detail.put("error", drift.error());
        if (drift.hasDrift()) {
            detail.put("remedy", "Use Pull from Access on the queue to import them, then decide them. "
                    + "Access holds an approval open until it receives a decision.");
        }
        return detail;
    }
}
