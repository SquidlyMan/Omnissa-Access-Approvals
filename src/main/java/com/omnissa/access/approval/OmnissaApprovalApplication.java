package com.omnissa.access.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;
import java.util.Properties;

@SpringBootApplication
@EnableScheduling
public class OmnissaApprovalApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmnissaApprovalApplication.class, args);
    }

    @Bean
    public ByteArrayHttpMessageConverter byteArrayHttpMessageConverter() {
        return new ByteArrayHttpMessageConverter();
    }

    // @Primary so MailNotification gets this (mailtemplates) Configuration, not the
    // web-MVC one Spring Boot auto-configures from spring-boot-starter-freemarker.
    @Bean
    @Primary
    public FreeMarkerConfigurationFactoryBean getFreeMarkerConfiguration() {
        FreeMarkerConfigurationFactoryBean bean = new FreeMarkerConfigurationFactoryBean();
        bean.setTemplateLoaderPath("classpath:/mailtemplates/");
        // Every ${...} in the mail templates is HTML-escaped. The requester's
        // name and the application's name arrive in the callout from Access,
        // and the decision note is whatever the approver typed; rendered raw
        // into a text/html message (as they were until 1.23.5), any of them
        // could carry markup — a link, say — that the requester would read as
        // the tool's own.
        Properties settings = new Properties();
        settings.setProperty("output_format", "freemarker.core.HTMLOutputFormat");
        bean.setFreemarkerSettings(settings);
        return bean;
    }
}
