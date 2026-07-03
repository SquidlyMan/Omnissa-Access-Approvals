package com.omnissa.access.approval.config;

import com.omnissa.access.approval.interfaces.ApprovalsInterface;
import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.CalloutResponse;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.repository.AutoRuleRepository;
import com.omnissa.access.approval.util.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Hourly evaluation of EXPIRY auto-rules: pending requests older than a
 * rule's expiryDays are automatically rejected.
 */
@Component
public class RuleScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RuleScheduler.class);

    @Autowired
    private AutoRuleRepository autoRuleRepository;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    @Autowired
    private ApprovalsInterface approvalsInterface;

    @Autowired
    private AuditService auditService;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void applyExpiryRules() {
        List<AutoRule> expiryRules = autoRuleRepository.findAll().stream()
                .filter(rule -> rule.isEnabled()
                        && rule.getExpiryDays() != null && rule.getExpiryDays() > 0
                        && "reject".equals(rule.getAction()))
                .toList();
        if (expiryRules.isEmpty()) {
            return;
        }
        for (AutoRule rule : expiryRules) {
            Date threshold = Date.from(Instant.now().minus(rule.getExpiryDays(), ChronoUnit.DAYS));
            for (CalloutRequest request : approvalsRepository.findByState("pending")) {
                if (request.getReceivedDate() == null || !request.getReceivedDate().before(threshold)) {
                    continue;
                }
                try {
                    logger.info("Expiry rule #{} rejecting stale pending requestId={}",
                            rule.getId(), request.getRequestId());
                    approvalsInterface.requestResponse(new CalloutResponse(
                            request.getRequestId(), false,
                            "Auto-rejected: pending longer than " + rule.getExpiryDays() + " days"));
                    auditService.record("auto-rejected", request.getRequestId(), request.getResourceName(),
                            "Auto-rejected by rule #" + rule.getId()
                                    + ": pending longer than " + rule.getExpiryDays() + " days");
                } catch (Exception e) {
                    logger.error("Expiry rule #{} failed for requestId={}",
                            rule.getId(), request.getRequestId(), e);
                }
            }
        }
    }
}
