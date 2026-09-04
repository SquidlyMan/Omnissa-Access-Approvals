package com.omnissa.access.approval.update;

import com.omnissa.access.approval.util.MailNotification;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The channels that can announce a newer release, each opt-in on its own.
 *
 * <p>The console banner is always on and is the primary surface; these exist
 * for the administrator who does not open the console daily. Both reuse the
 * delivery already configured — the same webhook URL and format, the same
 * SMTP relay — so switching one on is a single boolean.
 *
 * <p>Returns whether anything was delivered. {@link UpdateCheckService} stamps
 * the version as announced only on {@code true}, so a version first seen while
 * both toggles are off is still announced by whichever is switched on later,
 * and a delivery failure is retried on the next check rather than lost.
 */
@Component
public class UpdateNotifications implements UpdateNotifier {

    private static final Logger logger = LoggerFactory.getLogger(UpdateNotifications.class);

    private final WebhookNotifier webhook;
    private final MailNotification mail;
    private final boolean notifyWebhook;
    private final boolean notifyEmail;
    private final String emailTo;

    public UpdateNotifications(WebhookNotifier webhook,
                               MailNotification mail,
                               @Value("${omnissa.update.notify-webhook:false}") boolean notifyWebhook,
                               @Value("${omnissa.update.notify-email:false}") boolean notifyEmail,
                               @Value("${omnissa.update.notify-email-to:}") String emailTo) {
        this.webhook = webhook;
        this.mail = mail;
        this.notifyWebhook = notifyWebhook;
        this.notifyEmail = notifyEmail;
        this.emailTo = emailTo;
    }

    @Override
    public boolean updateAvailable(String runningVersion, String newestVersion) {
        boolean delivered = false;
        // Each channel on its own: one that throws must not discard the
        // other's delivery, or the same version is announced again on every
        // check — the once-only promise broken by the channel that did work.
        if (notifyWebhook) {
            try {
                delivered |= webhook.notifyUpdateAvailable(runningVersion, newestVersion);
            } catch (RuntimeException e) {
                logger.warn("Update webhook notification threw: {}", e.toString());
            }
        }
        if (notifyEmail) {
            if (emailTo == null || emailTo.isBlank()) {
                logger.warn("Update e-mail is enabled but omnissa.update.notify-email-to "
                        + "(OMNISSA_UPDATE_NOTIFY_EMAIL_TO) is blank — nobody to send it to");
            } else {
                try {
                    delivered |= mail.sendUpdateAvailable(emailTo, runningVersion, newestVersion);
                } catch (RuntimeException e) {
                    logger.warn("Update e-mail notification threw: {}", e.toString());
                }
            }
        }
        if (!notifyWebhook && !notifyEmail) {
            logger.info("Update available: {} (running {}). No notifier is enabled; the admin console shows it.",
                    newestVersion, runningVersion);
        }
        return delivered;
    }
}
