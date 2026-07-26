package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Records audit-trail entries for approval decisions and callout ingestion.
 * Every entry is persisted (for the admin UI) and mirrored to a dedicated
 * "AUDIT" slf4j logger so audit lines automatically flow into the file log,
 * log bundle, and syslog export.
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    private static final int MAX_MESSAGE_LENGTH = 1000;

    @Autowired
    private AuditEventRepository auditEventRepository;

    public void record(String action, String requestId, String resourceName, String message) {
        record(action, requestId, resourceName, message, null, Requester.UNKNOWN);
    }

    /**
     * Preferred form when the request is in hand: captures who the access was
     * for alongside who acted. The requester is copied onto the event rather
     * than looked up through the requestId later, because an admin can delete
     * a request record while its audit history remains.
     */
    public void recordFor(String action, CalloutRequest request, String message) {
        recordFor(action, request, message, null);
    }

    public void recordFor(String action, CalloutRequest request, String message, String actor) {
        record(action,
                request != null ? request.getRequestId() : null,
                request != null ? request.getResourceName() : null,
                message, actor, Requester.from(request));
    }

    /**
     * Record an audit entry attributed to an explicit actor. Used by decisions
     * that arrive without a Spring session — e.g. a Slack approval (#50) — so
     * the audit trail names the real approver instead of falling back to
     * "system". A null actor resolves from the security context as before.
     */
    public void record(String action, String requestId, String resourceName, String message, String actor) {
        record(action, requestId, resourceName, message, actor, Requester.UNKNOWN);
    }

    public void record(String action, String requestId, String resourceName, String message,
                       String actor, Requester requester) {
        String admin = (actor != null && !actor.isBlank()) ? actor : currentAdmin();
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        try {
            AuditEvent event = new AuditEvent();
            event.setTimestamp(new Date());
            event.setAdminUsername(admin);
            event.setAction(action);
            event.setRequestId(requestId);
            event.setResourceName(resourceName);
            if (requester != null) {
                event.setRequesterId(requester.id());
                event.setRequesterName(requester.name());
                event.setRequesterEmail(requester.email());
            }
            event.setMessage(message);
            auditEventRepository.save(event);
        } catch (Exception e) {
            logger.error("Failed to persist audit event action={} requestId={}", action, requestId, e);
        }
        auditLogger.info("AUDIT action={} admin={} requester={} requestId={} app={} message={}",
                action, admin,
                requester != null && requester.isKnown() ? requester.label() : "-",
                requestId, resourceName, message);
    }

    /**
     * Resolves the acting admin from the security context: OIDC users by
     * preferred_username/email, local users by username, and unauthenticated
     * threads (callout ingestion, schedulers) as "system".
     */
    public String currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            if (oidcUser.getPreferredUsername() != null && !oidcUser.getPreferredUsername().isBlank()) {
                return oidcUser.getPreferredUsername();
            }
            if (oidcUser.getEmail() != null && !oidcUser.getEmail().isBlank()) {
                return oidcUser.getEmail();
            }
            return auth.getName();
        }
        if (principal instanceof UserAccount userAccount) {
            return userAccount.getUsername();
        }
        return auth.getName();
    }
}
