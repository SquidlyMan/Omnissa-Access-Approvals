package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.ApprovalStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalStageRepository extends JpaRepository<ApprovalStage, Long> {

    List<ApprovalStage> findByChainIdOrderByStageOrderAsc(Long chainId);

    void deleteByChainId(Long chainId);
}
