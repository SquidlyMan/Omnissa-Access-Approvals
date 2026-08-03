package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.ApprovalStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ApprovalStageRepository extends JpaRepository<ApprovalStage, Long> {

    List<ApprovalStage> findByChainIdOrderByStageOrderAsc(Long chainId);

    /**
     * {@code @Modifying} + {@code @Transactional} are load-bearing, not
     * decoration. A Spring Data derived delete needs an active transaction to
     * issue its DELETE, and without one the failure is horribly conditional:
     * a call that finds nothing to delete issues no DELETE and appears to
     * work, while the moment there are rows it throws
     * {@code TransactionRequiredException} and the caller 500s.
     *
     * <p>That is exactly how it shipped — saving a chain's stages worked the
     * first time and failed every time after, and deleting a chain worked
     * only while it had no stages.
     */
    @Modifying
    @Transactional
    void deleteByChainId(Long chainId);
}
