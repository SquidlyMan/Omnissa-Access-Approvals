package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.ApprovalChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalChainRepository extends JpaRepository<ApprovalChain, Long> {
}
