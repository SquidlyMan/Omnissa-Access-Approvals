package com.omnissa.access.approval.util;

import com.omnissa.access.approval.OmnissaApprovalApplication;
import freemarker.template.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What goes into a requester's e-mail comes from the callout (the requester's
 * name, the application's name) and from the approver (the decision note).
 * Until 1.23.5 the templates rendered all of it raw into a text/html message,
 * so a note of {@code <a href="https://evil/…">Re-enter your password</a>}
 * reached the requester as a link from the tool's own sender address. The
 * real mail configuration renders the real templates here.
 */
class MailTemplateEscapingTest {

    private static Configuration mailConfiguration() throws Exception {
        FreeMarkerConfigurationFactoryBean bean = new OmnissaApprovalApplication().getFreeMarkerConfiguration();
        bean.afterPropertiesSet();
        return bean.getObject();
    }

    private static String render(String template, Map<String, Object> request) throws Exception {
        Map<String, Object> model = new HashMap<>();
        model.put("request", request);
        model.put("omnissaURL", "https://tenant.example.com");
        return FreeMarkerTemplateUtils.processTemplateIntoString(mailConfiguration().getTemplate(template), model);
    }

    @Test
    @DisplayName("markup in the request fields and the decision note is escaped")
    void markupIsEscaped() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("resourceName", "Payroll <script>alert(1)</script>");
        request.put("responseMessage", "<a href=\"https://evil.example/\">Re-enter your password</a>");
        request.put("userAttributes", Map.of("firstName", List.of("<b>Dean</b>"), "lastName", List.of("Flaming")));

        for (String template : List.of("approved.ftl", "denied.ftl")) {
            String html = render(template, request);
            assertThat(html).as(template)
                    .contains("Payroll &lt;script&gt;alert(1)&lt;/script&gt;")
                    .doesNotContain("<script>")
                    .contains("&lt;a href=")
                    .doesNotContain("<a href=\"https://evil.example/\">")
                    .contains("&lt;b&gt;Dean&lt;/b&gt;")
                    .doesNotContain("<b>Dean</b>");
        }
    }

    @Test
    @DisplayName("a request without directory attributes still renders")
    void missingUserAttributesStillRenders() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("resourceName", "Payroll");

        for (String template : List.of("approved.ftl", "denied.ftl")) {
            String html = render(template, request);
            assertThat(html).as(template).contains("Hi").contains("Payroll");
        }
    }
}
