package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    /** Rows written before the requester columns existed (#60 backfill). */
    List<AuditEvent> findByRequesterIdIsNullAndRequestIdIsNotNull();
}
