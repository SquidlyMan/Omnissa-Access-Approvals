package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.AutoRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AutoRuleRepository extends JpaRepository<AutoRule, Long> {
}
