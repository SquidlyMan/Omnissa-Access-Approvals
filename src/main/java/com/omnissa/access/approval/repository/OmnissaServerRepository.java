package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.OmnissaServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OmnissaServerRepository extends JpaRepository<OmnissaServer, Long> {
}
