package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.CalloutRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalsRepository extends JpaRepository<CalloutRequest, Long> {

    List<CalloutRequest> findByState(String state);

    /**
     * JIT expiry sweep (#49): approved grants whose access has expired. The
     * {@code accessExpiresAt < :when} predicate is null-safe — permanent grants
     * (null expiry) never match, so only time-bound grants are returned.
     */
    List<CalloutRequest> findByStateAndAccessExpiresAtBefore(String state, java.util.Date when);

    /**
     * JIT re-request restore sweep (#49, Option 2): revoked grants whose hold has
     * elapsed and are due to have the exclusion lifted. {@code restoreAt} is set
     * only for re-requestable grants and cleared once restored, so this returns
     * only requests still awaiting restore.
     */
    List<CalloutRequest> findByStateAndRestoreAtBefore(String state, java.util.Date when);
    Page<CalloutRequest> findByStateOrderByIdDesc(String state, Pageable pageable);
    Page<CalloutRequest> findByStateInOrderByIdDesc(List<String> states, Pageable pageable);
    Page<CalloutRequest> findByStateAndResourceName(String state, String resourceName, Pageable pageable);
    /**
     * Every record carrying this request id — normally none or one.
     *
     * @see #findByRequestId for why "normally" is not "always"
     */
    List<CalloutRequest> findAllByRequestIdOrderByIdAsc(String requestId);

    /**
     * The record for a request id, tolerating duplicates.
     *
     * <p>This was a derived query returning a single entity, which throws
     * {@link org.springframework.dao.IncorrectResultSizeDataAccessException}
     * the moment two rows share a request id. Sixteen call sites use it — the
     * queue detail view, every decision path, the expiry sweeps, the mail
     * notifier — so a single duplicated row took all of them out at once with a
     * 500, and the request became impossible to open or decide.
     *
     * <p>Duplicates happen because <strong>Omnissa Access delivers each callout
     * from more than one node</strong>. Two POSTs carrying the same request id
     * arrived 25ms apart from different egress addresses and both were ingested.
     * That is ordinary at-least-once delivery and the endpoint must expect it.
     *
     * <p>It went unnoticed until callout authentication started working: while
     * one leg was being rejected with a 401, only one copy ever reached the
     * database, so a broken handshake was accidentally deduplicating the queue.
     * Fixing authentication removed the accident and exposed this.
     *
     * <p>Returns the <em>earliest</em> row, so behaviour is deterministic rather
     * than dependent on scan order, and warns when there is more than one — the
     * duplicate still wants cleaning up, it just no longer breaks the page.
     */
    default CalloutRequest findByRequestId(String requestId) {
        List<CalloutRequest> matches = findAllByRequestIdOrderByIdAsc(requestId);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            org.slf4j.LoggerFactory.getLogger(ApprovalsRepository.class).warn(
                    "{} records share requestId {} — Omnissa Access delivers callouts from "
                            + "multiple nodes, so a duplicate reached the database. Using the "
                            + "earliest (id={}); the extra row(s) can be deleted from the queue.",
                    matches.size(), requestId, matches.get(0).getId());
        }
        return matches.get(0);
    }
    Integer countByState(String state);
    Integer countByResourceName(String resourceName);

    @Query("SELECT DISTINCT c.resourceName FROM CalloutRequest c")
    List<String> findDistinctResourceNames();
}
