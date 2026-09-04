package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Notifies the requester by e-mail once their request has been decided.
 *
 * <p>Mail is optional, and this class has to hold that as an invariant rather
 * than an assumption. Spring Boot auto-configures a {@link JavaMailSender} only
 * when {@code spring.mail.host} is set, so injecting one as a required
 * dependency made an unset SMTP relay a startup failure — <em>"Field mailSender
 * ... required a bean of type 'JavaMailSender' that could not be found"</em> —
 * for an install that had simply never been given a relay. That also
 * contradicted {@code management.health.mail.enabled=false}, which exists
 * precisely so an absent relay does not affect health.
 *
 * <p>So the sender is resolved per call, and a missing one is <em>announced</em>
 * rather than swallowed: a decision still succeeds, and the log says plainly
 * that the requester was not told and which property would fix it. Silence
 * would be worse than the crash it replaced — an approver would believe the
 * requester had been notified.
 */
@Service
public class MailNotification {

    private static final Logger logger = LoggerFactory.getLogger(MailNotification.class);

    /**
     * Absent unless {@code spring.mail.host} is configured. An
     * {@link ObjectProvider} rather than {@code @Autowired(required = false)}
     * so that "no relay" is a value this code has to handle at the point of
     * use, not a null field that reads as an oversight.
     */
    @Autowired
    private ObjectProvider<JavaMailSender> mailSender;

    @Autowired
    private Configuration freeMarkerConfig;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    // Sender address — must be an accepted/authorized address on your SMTP
    // relay (e.g. the HVE account for Office 365). Env: SPRING_MAIL_FROM.
    @Value("${spring.mail.from:no-reply@example.com}")
    private String fromAddress;

    public void sendEmailNotification(String requestId, boolean approved) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            logger.warn("No e-mail sent for requestId={}: mail is not configured. Set "
                    + "spring.mail.host (SPRING_MAIL_HOST) to notify requesters of decisions.",
                    requestId);
            return;
        }
        CalloutRequest calloutRequest = approvalsRepository.findByRequestId(requestId);
        String template = approved ? "approved.ftl" : "denied.ftl";
        MimeMessagePreparator preparator = getMessagePreparator(calloutRequest, template);
        try {
            sender.send(preparator);
        } catch (MailException e) {
            logger.error("Failed to send email notification for requestId={}", requestId, e);
        }
    }

    /**
     * A newer release is published (#83). Plain text; there is no request to
     * template against. Returns whether it was sent, so the caller can decide
     * whether the version counts as announced.
     */
    public boolean sendUpdateAvailable(String to, String runningVersion, String newestVersion) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            logger.warn("No update e-mail sent: mail is not configured. Set spring.mail.host "
                    + "(SPRING_MAIL_HOST), or turn off OMNISSA_UPDATE_NOTIFY_EMAIL.");
            return false;
        }
        String omnissaURL = RestPreconditions.omnissaServerBaseUrl();
        try {
            sender.send((MimeMessage mimeMessage) -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false);
                helper.setSubject("Access Approval Tool " + newestVersion + " is available");
                helper.setFrom(fromAddress);
                helper.setTo(to);
                helper.setText("A newer release of the Access Approval Tool is published.\n\n"
                        + "  Available : " + newestVersion + "\n"
                        + "  Running   : " + runningVersion + "\n"
                        + "  Tenant    : " + omnissaURL + "\n\n"
                        + "Nothing installs until an administrator approves it on the Dashboard.\n", false);
            });
            logger.info("Update e-mail sent to {}: {} available (running {})", to, newestVersion, runningVersion);
            return true;
        } catch (MailException e) {
            logger.error("Failed to send update e-mail to {}", to, e);
            return false;
        }
    }

    private String getMail(CalloutRequest request) {
        if (request.getUserAttributes() == null
                || request.getUserAttributes().get("email") == null
                || request.getUserAttributes().get("email").size() != 1) {
            return null;
        }
        return request.getUserAttributes().get("email").get(0);
    }

    private String getMailTemplateContent(Map<String, Object> model, String template) {
        try {
            return FreeMarkerTemplateUtils.processTemplateIntoString(
                    freeMarkerConfig.getTemplate(template), model);
        } catch (IOException | TemplateException e) {
            logger.error("Failed to render mail template {}", template, e);
            return "";
        }
    }

    private MimeMessagePreparator getMessagePreparator(final CalloutRequest request, String template) {
        return (MimeMessage mimeMessage) -> {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true);
            String omnissaURL = RestPreconditions.omnissaServerBaseUrl();

            helper.setSubject("Your Omnissa Access Application Request has been processed");
            helper.setFrom(fromAddress);
            helper.setTo(getMail(request));

            Map<String, Object> model = new HashMap<>();
            model.put("request", request);
            model.put("omnissaURL", omnissaURL);

            helper.setText(getMailTemplateContent(model, template), true);
        };
    }
}
