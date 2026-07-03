package com.omnissa.access.approval.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.net.SyslogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Optional syslog forwarding. When syslog.host (SYSLOG_HOST) is set, attaches
 * a logback SyslogAppender to the root logger at startup so all application
 * logs are also shipped via UDP syslog. Does nothing when the host is blank.
 */
@Component
public class SyslogConfig implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SyslogConfig.class);

    @Value("${syslog.host:}")
    private String syslogHost;

    @Value("${syslog.port:514}")
    private int syslogPort;

    @Override
    public void run(ApplicationArguments args) {
        if (syslogHost.isBlank()) {
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        SyslogAppender appender = new SyslogAppender();
        appender.setContext(context);
        appender.setName("SYSLOG");
        appender.setSyslogHost(syslogHost);
        appender.setPort(syslogPort);
        appender.setFacility("USER");
        appender.setSuffixPattern("omnissa-approvals: [%thread] %logger %msg");
        appender.start();

        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);

        logger.info("Syslog forwarding is active to {}:{}", syslogHost, syslogPort);
    }
}
