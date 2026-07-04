package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class MailNotification {

    private static final Logger logger = LoggerFactory.getLogger(MailNotification.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private Configuration freeMarkerConfig;

    @Autowired
    private ApprovalsRepository approvalsRepository;

    // Sender address — must be an accepted/authorized address on your SMTP
    // relay (e.g. the HVE account for Office 365). Env: SPRING_MAIL_FROM.
    @Value("${spring.mail.from:no-reply@example.com}")
    private String fromAddress;

    public void sendEmailNotification(String requestId, boolean approved) {
        CalloutRequest calloutRequest = approvalsRepository.findByRequestId(requestId);
        String template = approved ? "approved.ftl" : "denied.ftl";
        MimeMessagePreparator preparator = getMessagePreparator(calloutRequest, template);
        try {
            mailSender.send(preparator);
        } catch (MailException e) {
            logger.error("Failed to send email notification for requestId={}", requestId, e);
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
