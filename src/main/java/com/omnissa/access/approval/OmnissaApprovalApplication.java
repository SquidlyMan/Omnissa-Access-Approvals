package com.omnissa.access.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;

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
        return bean;
    }
}
