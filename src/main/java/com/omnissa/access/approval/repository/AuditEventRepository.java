package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);
}
