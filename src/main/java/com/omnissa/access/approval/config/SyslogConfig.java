package com.omnissa.access.approval.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.net.SyslogAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.omnissa.access.approval.util.TcpSyslogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Optional syslog forwarding. When syslog.host (SYSLOG_HOST) is set, attaches
 * a syslog appender to the root logger at startup so all application logs are
 * also shipped to a syslog server. The transport is selected by
 * syslog.protocol (SYSLOG_PROTOCOL): "udp" (default — logback's built-in
 * SyslogAppender), "tcp" (newline-framed RFC 3164 over a persistent TCP
 * socket), or "tls" (TCP + TLS, with optional CA pinning and client
 * certificate auth — see {@link TcpSyslogAppender}). Does nothing when the
 * host is blank.
 */
@Component
public class SyslogConfig implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SyslogConfig.class);

    @Value("${syslog.host:}")
    private String syslogHost;

    @Value("${syslog.port:514}")
    private int syslogPort;

    @Value("${syslog.protocol:udp}")
    private String syslogProtocol;

    // TLS material (used only when syslog.protocol=tls). File variants take
    // precedence over the inline PEM variants when both are set.
    @Value("${syslog.client-cert-pem:}")
    private String clientCertPem;

    @Value("${syslog.client-key-pem:}")
    private String clientKeyPem;

    @Value("${syslog.ca-pem:}")
    private String caPem;

    @Value("${syslog.client-cert-file:}")
    private String clientCertFile;

    @Value("${syslog.client-key-file:}")
    private String clientKeyFile;

    @Value("${syslog.ca-file:}")
    private String caFile;

    @Override
    public void run(ApplicationArguments args) {
        if (syslogHost.isBlank()) {
            return;
        }

        String protocol = syslogProtocol == null ? "udp"
                : syslogProtocol.trim().toLowerCase(Locale.ROOT);
        if (!"udp".equals(protocol) && !"tcp".equals(protocol) && !"tls".equals(protocol)) {
            logger.warn("Unknown syslog.protocol '{}' (expected udp, tcp, or tls) — falling back to udp",
                    syslogProtocol);
            protocol = "udp";
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        Appender<ILoggingEvent> appender = "udp".equals(protocol)
                ? buildUdpAppender(context)
                : buildTcpAppender(context, "tls".equals(protocol));
        appender.start();
        if (!appender.isStarted()) {
            logger.warn("Syslog appender failed to start ({} to {}:{}) — syslog forwarding is "
                    + "disabled; check the syslog TLS certificate/key configuration",
                    protocol, syslogHost, syslogPort);
            return;
        }

        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);

        logger.info("Syslog forwarding is active to {}:{} via {}", syslogHost, syslogPort, protocol);
    }

    private Appender<ILoggingEvent> buildUdpAppender(LoggerContext context) {
        SyslogAppender appender = new SyslogAppender();
        appender.setContext(context);
        appender.setName("SYSLOG");
        appender.setSyslogHost(syslogHost);
        appender.setPort(syslogPort);
        appender.setFacility("USER");
        appender.setSuffixPattern("omnissa-approvals: [%thread] %logger %msg");
        return appender;
    }

    private Appender<ILoggingEvent> buildTcpAppender(LoggerContext context, boolean useTls) {
        TcpSyslogAppender appender = new TcpSyslogAppender();
        appender.setContext(context);
        appender.setName("SYSLOG");
        appender.setHost(syslogHost);
        appender.setPort(syslogPort);
        appender.setUseTls(useTls);
        appender.setClientCertPem(clientCertPem);
        appender.setClientKeyPem(clientKeyPem);
        appender.setCaPem(caPem);
        appender.setClientCertFile(clientCertFile);
        appender.setClientKeyFile(clientKeyFile);
        appender.setCaFile(caFile);
        return appender;
    }
}
