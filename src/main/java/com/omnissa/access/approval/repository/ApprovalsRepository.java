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
    Page<CalloutRequest> findByStateOrderByIdDesc(String state, Pageable pageable);
    Page<CalloutRequest> findByStateAndResourceName(String state, String resourceName, Pageable pageable);
    CalloutRequest findByRequestId(String requestId);
    Integer countByState(String state);
    Integer countByResourceName(String resourceName);

    @Query("SELECT DISTINCT c.resourceName FROM CalloutRequest c")
    List<String> findDistinctResourceNames();
}
