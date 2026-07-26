package com.omnissa.access.approval.config;

import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.repository.AuditEventRepository;
import com.omnissa.access.approval.util.Requester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time backfill of the requester columns added in #60.
 *
 * <p>Audit events written before those columns existed name who acted but not
 * who the access was for. Where the originating request still exists the
 * identity can be recovered from it; where the request has since been deleted
 * it is gone for good — which is the very reason the columns now live on the
 * event rather than being resolved through the request.
 *
 * <p>The unresolvable count is logged rather than passed over in silence, so
 * the trail is not mistaken for being complete when part of it cannot be.
 */
@Component
@Order(3)
public class AuditRequesterBackfill implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AuditRequesterBackfill.class);

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<AuditEvent> pending = auditEventRepository.findByRequesterIdIsNullAndRequestIdIsNotNull();
        if (pending.isEmpty()) {
            return;
        }

        Map<String, Requester> resolved = new HashMap<>();
        List<AuditEvent> updated = new ArrayList<>();
        int unresolvable = 0;

        for (AuditEvent event : pending) {
            Requester requester = resolved.computeIfAbsent(event.getRequestId(), requestId -> {
                CalloutRequest request = approvalsRepository.findByRequestId(requestId);
                return request == null ? Requester.UNKNOWN : Requester.from(request);
            });

            if (!requester.isKnown()) {
                unresolvable++;
                continue;
            }
            event.setRequesterId(requester.id());
            event.setRequesterName(requester.name());
            event.setRequesterEmail(requester.email());
            updated.add(event);
        }

        if (!updated.isEmpty()) {
            auditEventRepository.saveAll(updated);
        }
        logger.info("Audit requester backfill: {} event(s) updated, {} unresolvable "
                        + "(originating request no longer exists)",
                updated.size(), unresolvable);
    }
}
