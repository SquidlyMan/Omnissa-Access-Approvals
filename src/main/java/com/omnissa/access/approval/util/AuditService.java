package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AuditEvent;
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
        String admin = currentAdmin();
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
            event.setMessage(message);
            auditEventRepository.save(event);
        } catch (Exception e) {
            logger.error("Failed to persist audit event action={} requestId={}", action, requestId, e);
        }
        auditLogger.info("AUDIT action={} admin={} requestId={} app={} message={}",
                action, admin, requestId, resourceName, message);
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
