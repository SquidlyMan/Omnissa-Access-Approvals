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
    CalloutRequest findByRequestId(String requestId);
    Integer countByState(String state);
    Integer countByResourceName(String resourceName);

    @Query("SELECT DISTINCT c.resourceName FROM CalloutRequest c")
    List<String> findDistinctResourceNames();
}
