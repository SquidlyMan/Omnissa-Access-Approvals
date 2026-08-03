package com.omnissa.access.approval.service;

import com.omnissa.access.approval.controller.SseController;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.util.AuditService;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Claim, release and assign (#51) — visible ownership of a pending request.
 *
 * <p><strong>Advisory, never authorization</strong> (design decision D1).
 * Any {@code APPROVER} may decide any request whether it is claimed, claimed
 * by someone else, or unclaimed. Making a claim authoritative would make a
 * request undecidable the moment its owner became unavailable — a convenience
 * turned into an outage — and would put decision authority in a column
 * Omnissa Access has never heard of, which is the shape of the approver-map
 * feature this project already removed once for failing open.
 *
 * <p>Release is deliberately unrestricted for the same reason: the
 * alternative is a request welded to someone who has left.
 */
@Service
public class DelegationService {

    /** Outcome of a claim/assign attempt, so the caller can report precisely. */
    public enum Outcome {
        /** Applied. */
        OK,
        /** No such request. */
        NOT_FOUND,
        /** Only a pending request can be claimed, released or assigned. */
        NOT_PENDING,
        /** Already held by someone else — claiming does not steal. */
        ALREADY_HELD
    }

    public record Result(Outcome outcome, String message, CalloutRequest request) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    @Autowired private ApprovalsRepository approvalsRepository;
    @Autowired private AuditService auditService;
    @Autowired private SseController sseController;

    /** Takes ownership for {@code actor} themselves. */
    public Result claim(String requestId, String actor) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        Result guard = guardPending(request);
        if (guard != null) {
            return guard;
        }
        String held = request.getAssignedOwner();
        if (held != null && !held.isBlank() && !held.equalsIgnoreCase(actor)) {
            return new Result(Outcome.ALREADY_HELD,
                    "This request is already held by " + held + ". Release it first, or simply decide it — "
                            + "a claim never blocks anyone from deciding.", request);
        }
        return apply(request, actor, actor, "request-claimed",
                "Claimed by " + actor);
    }

    /**
     * Hands the request to a named approver. The assignee is <em>not</em>
     * required to act — escalation still fires on schedule (D3) and the claim
     * TTL still auto-releases, so an assignment that is never actioned decays
     * exactly like an abandoned self-claim rather than parking the request
     * behind one person indefinitely.
     */
    public Result assign(String requestId, String assignee, String actor) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        Result guard = guardPending(request);
        if (guard != null) {
            return guard;
        }
        if (assignee == null || assignee.isBlank()) {
            return new Result(Outcome.NOT_FOUND, "No assignee given.", request);
        }
        String note = assignee.equalsIgnoreCase(actor)
                ? "Claimed by " + actor
                : "Assigned to " + assignee + " by " + actor;
        return apply(request, assignee, actor, "request-claimed", note);
    }

    /** Gives the request back to the pool. Any approver may release any claim. */
    public Result release(String requestId, String actor) {
        CalloutRequest request = approvalsRepository.findByRequestId(requestId);
        Result guard = guardPending(request);
        if (guard != null) {
            return guard;
        }
        String owner = request.getAssignedOwner();
        if (owner == null || owner.isBlank()) {
            return new Result(Outcome.OK, "Already unclaimed.", request);
        }
        long heldMinutes = request.getAssignedAt() == null ? 0
                : Duration.between(request.getAssignedAt().toInstant(), Instant.now()).toMinutes();

        request.setAssignedOwner(null);
        request.setAssignedAt(null);
        approvalsRepository.save(request);
        // Names the owner released and how long they held it, or a handover
        // chain cannot be reconstructed. A manual release is distinguished
        // from a TTL lapse by the actor: a person here, "system" there.
        auditService.recordFor("request-released", request,
                "Claim by " + owner + " released by " + actor + " after "
                        + WebhookNotifier.humanDuration((int) heldMinutes), actor);
        sseController.publishQueueUpdate("queue-updated");
        return new Result(Outcome.OK, "Released.", request);
    }

    private Result apply(CalloutRequest request, String owner, String actor, String action, String note) {
        request.setAssignedOwner(owner);
        request.setAssignedAt(new Date());
        approvalsRepository.save(request);
        auditService.recordFor(action, request, note, actor);
        sseController.publishQueueUpdate("queue-updated");
        return new Result(Outcome.OK, note, request);
    }

    private Result guardPending(CalloutRequest request) {
        if (request == null) {
            return new Result(Outcome.NOT_FOUND, "Request not found.", null);
        }
        if (!"pending".equalsIgnoreCase(request.getState())) {
            return new Result(Outcome.NOT_PENDING,
                    "Only a pending request can be claimed or assigned (this one is "
                            + request.getState() + ").", request);
        }
        return null;
    }
}
