package com.omnissa.access.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SMTP operation must be bounded (#68).
 *
 * <p>Jakarta Mail defaults {@code connectiontimeout}, {@code timeout} and
 * {@code writetimeout} to <strong>infinite</strong>, and
 * {@code MailNotification} sends synchronously. A relay that silently drops
 * packets rather than refusing the connection — a firewalled port 25, which is
 * ordinary rather than exotic — therefore parked the calling thread with no
 * recovery short of a restart. A refused connection fails fast and is harmless;
 * a blackholed one is what hangs.
 *
 * <p>This asserts the timeouts reach the mail sender rather than merely
 * appearing in {@code application.properties}. Those are different claims: a
 * misspelled key, a move to a profile-specific file, or a future
 * {@code JavaMailSender} bean built by hand would all leave the properties
 * present and the sender unbounded — which is exactly the shape of failure this
 * project keeps producing, something reporting success while not being true.
 *
 * <p>The values are deliberately not asserted to be any particular number. Ten
 * seconds is a judgement call and may reasonably change; being finite is the
 * contract.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mail-timeout;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // A host is what makes Boot auto-configure the sender at all. Never
        // contacted: nothing here sends mail.
        "spring.mail.host=smtp.invalid"
})
class MailTimeoutTest {

    private static final String CONNECT = "mail.smtp.connectiontimeout";
    private static final String READ    = "mail.smtp.timeout";
    private static final String WRITE   = "mail.smtp.writetimeout";

    @Autowired
    private JavaMailSender mailSender;

    @Test
    @DisplayName("connect, read and write timeouts all reach the configured mail sender")
    void everySmtpOperationIsBounded() {
        assertThat(mailSender)
                .as("the shipped configuration must produce a JavaMailSenderImpl whose "
                        + "properties can be inspected; if this changes, the timeouts need "
                        + "re-verifying by whatever mechanism replaced it")
                .isInstanceOf(JavaMailSenderImpl.class);

        Properties props = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();

        for (String key : new String[]{CONNECT, READ, WRITE}) {
            String value = props.getProperty(key);
            assertThat(value)
                    .as("%s is unset, so Jakarta Mail will treat it as infinite. A relay that "
                            + "drops packets instead of refusing them then blocks the sending "
                            + "thread until the process restarts.", key)
                    .isNotNull();
            assertThat(Integer.parseInt(value))
                    .as("%s must be a positive, finite number of milliseconds", key)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("the timeouts are short enough that a hung relay cannot hold a thread for long")
    void theBoundIsSmallEnoughToMatter() {
        Properties props = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();

        for (String key : new String[]{CONNECT, READ, WRITE}) {
            assertThat(Integer.parseInt(props.getProperty(key)))
                    .as("%s is set but so large that bounding it achieves nothing. The point "
                            + "is that a stuck send fails while somebody is still watching.", key)
                    .isLessThanOrEqualTo(60_000);
        }
    }
}
