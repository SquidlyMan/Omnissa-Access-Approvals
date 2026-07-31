package com.omnissa.access.approval.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the callout endpoint would accept anonymous requests
 * and nobody has said that is intended.
 *
 * <p>{@code POST /api/approvals/new} is the one path that must be reachable from
 * the internet, because the Omnissa Access cloud does the POSTing. Basic
 * authentication on it has always been supported — Access has Username and
 * Password fields in its approvals settings — but it was optional and blank by
 * default, so the shipped configuration accepted approval requests from anyone
 * who could reach the URL. Injected requests would appear in the queue as
 * genuine, and approving one grants real entitlements in the tenant.
 *
 * <p>Being optional-and-off is the part that fails quietly. An operator who
 * never sets the credentials gets a working system, no error, and no indication
 * the door is open. So the choice is made explicit: set credentials, or declare
 * that anonymous ingest is intended. Refusing to start is deliberate — it is
 * visible immediately and at a moment when someone is watching, which is not
 * true of a warning in a log nobody reads.
 */
@Component
public class CalloutAuthenticationGuard {

    private static final Logger logger =
            LoggerFactory.getLogger(CalloutAuthenticationGuard.class);

    private static final String MESSAGE = """
            The Omnissa Access callout endpoint (POST /api/approvals/new) is \
            reachable without authentication.

            This endpoint accepts approval requests and is the one path that has \
            to face the internet. Left unauthenticated, anyone who can reach the \
            URL can inject requests that are indistinguishable from real ones in \
            the queue.

            Choose one:

              1. Set OMNISSA_API_USERNAME and OMNISSA_API_PASSWORD, and enter the \
            same values under Settings -> Approvals in the Omnissa Access console. \
            This is the recommended option.

              2. Set OMNISSA_API_ALLOW_UNAUTHENTICATED=true if the endpoint is \
            genuinely unreachable from anywhere untrusted — a closed lab, for \
            example. The application will start and repeat this as a warning.

            Startup is refused rather than continuing, because an open ingest \
            path that nobody chose is worth interrupting a deployment for.""";

    @Value("${omnissa.api.username:}")
    private String username;

    @Value("${omnissa.api.allow-unauthenticated:false}")
    private boolean allowUnauthenticated;

    /**
     * Whether this installation has been pointed at a tenant yet.
     *
     * <p>This is what keeps the check from undoing #67, which made the tool
     * start with nothing configured at all — stand the container up, confirm it
     * serves, then point it at Access. Demanding a credential before that first
     * start would put the stack trace back on the new operator's screen, which
     * is the exact problem #67 removed.
     *
     * <p>It is also the honest risk boundary rather than a convenience. With no
     * tenant there is nothing an injected request can reach: no entitlement to
     * grant, no exclusion to lift, and an approval decision has nowhere to be
     * delivered. The endpoint only becomes worth attacking once it is wired to
     * Access, which is exactly when this starts insisting.
     */
    @Value("${omnissa.bootstrap.url:}")
    private String tenantUrl;

    private boolean anonymousIngest;

    @PostConstruct
    void check() {
        boolean tenantConfigured = tenantUrl != null && !tenantUrl.isBlank();
        anonymousIngest = tenantConfigured && (username == null || username.isBlank());
        if (!anonymousIngest) {
            return;
        }
        if (!allowUnauthenticated) {
            throw new IllegalStateException(MESSAGE);
        }
        warn();
    }

    /**
     * Repeated rather than said once at boot. A container that has been up for
     * months scrolled its startup log away long ago, and this is a standing
     * condition rather than a past event.
     */
    @Scheduled(fixedRate = 3_600_000L, initialDelay = 3_600_000L)
    void remind() {
        if (anonymousIngest) {
            warn();
        }
    }

    private void warn() {
        logger.warn("The callout endpoint accepts unauthenticated requests "
                + "(OMNISSA_API_ALLOW_UNAUTHENTICATED=true). Anyone who can reach "
                + "POST /api/approvals/new can inject approval requests. Set "
                + "OMNISSA_API_USERNAME and OMNISSA_API_PASSWORD to close it.");
    }
}
