package com.omnissa.access.approval.update;

import com.omnissa.access.approval.util.MailNotification;
import com.omnissa.access.approval.util.WebhookNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opt-in channels (#83, acceptance criterion 7). */
class UpdateNotificationsTest {

    private final WebhookNotifier webhook = mock(WebhookNotifier.class);
    private final MailNotification mail = mock(MailNotification.class);

    @Test
    @DisplayName("nothing fires with both toggles off, and nothing counts as delivered")
    void bothOff() {
        UpdateNotifications n = new UpdateNotifications(webhook, mail, false, false, "");
        assertThat(n.updateAvailable("1.21.1", "1.22.0")).isFalse();
        verify(webhook, never()).notifyUpdateAvailable(anyString(), anyString());
        verify(mail, never()).sendUpdateAvailable(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("webhook on, email off — only the webhook fires")
    void webhookOnly() {
        when(webhook.notifyUpdateAvailable("1.21.1", "1.22.0")).thenReturn(true);
        UpdateNotifications n = new UpdateNotifications(webhook, mail, true, false, "");
        assertThat(n.updateAvailable("1.21.1", "1.22.0")).isTrue();
        verify(mail, never()).sendUpdateAvailable(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("email on with a recipient — only the email fires")
    void emailOnly() {
        when(mail.sendUpdateAvailable("dean@example.com", "1.21.1", "1.22.0")).thenReturn(true);
        UpdateNotifications n = new UpdateNotifications(webhook, mail, false, true, "dean@example.com");
        assertThat(n.updateAvailable("1.21.1", "1.22.0")).isTrue();
        verify(webhook, never()).notifyUpdateAvailable(anyString(), anyString());
    }

    @Test
    @DisplayName("email on with no recipient is a warning, not a crash, and not a delivery")
    void emailNoRecipient() {
        UpdateNotifications n = new UpdateNotifications(webhook, mail, false, true, " ");
        assertThat(n.updateAvailable("1.21.1", "1.22.0")).isFalse();
        verify(mail, never()).sendUpdateAvailable(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a failed webhook is not 'delivered' — the version stays unannounced for the next check")
    void failedDeliveryIsNotDelivered() {
        when(webhook.notifyUpdateAvailable(anyString(), anyString())).thenReturn(false);
        UpdateNotifications n = new UpdateNotifications(webhook, mail, true, false, "");
        assertThat(n.updateAvailable("1.21.1", "1.22.0")).isFalse();
    }
}
