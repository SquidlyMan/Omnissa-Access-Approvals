package com.omnissa.access.approval.config;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * When SSL is enabled (server.ssl.key-store is set), adds a plain HTTP connector
 * on server.http.port that redirects every request to the HTTPS port.
 * Not needed in the Caddy deployment mode — Caddy handles TLS termination externally.
 *
 * <p>Two halves, and both are needed. The connector's {@code redirectPort} only
 * says <em>where</em> to send a request that must be confidential; Tomcat
 * decides <em>whether</em> a request must be from a security constraint with a
 * {@code CONFIDENTIAL} transport guarantee. Until 1.23.5 only the connector
 * existed, so the HTTP port served the whole application in the clear — login
 * form included — while the compose file promised a redirect.
 */
@Configuration
@ConditionalOnProperty(name = "server.ssl.key-store")
public class SslRedirectConfig {

    private final int httpsPort;
    private final int httpPort;

    public SslRedirectConfig(@Value("${server.port:8443}") int httpsPort,
                             @Value("${server.http.port:8080}") int httpPort) {
        this.httpsPort = httpsPort;
        this.httpPort = httpPort;
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpToHttpsRedirect() {
        return factory -> {
            factory.addAdditionalConnectors(httpConnector());
            factory.addContextCustomizers(SslRedirectConfig::requireConfidentialTransport);
        };
    }

    private Connector httpConnector() {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("http");
        connector.setPort(httpPort);
        connector.setSecure(false);
        connector.setRedirectPort(httpsPort);
        return connector;
    }

    /**
     * Every path must arrive over a confidential transport. A request on the
     * plain connector fails that test and Tomcat answers with a redirect to the
     * same path on the connector's redirect port, over https. The HTTPS
     * connector is secure, so it is unaffected.
     */
    static void requireConfidentialTransport(Context context) {
        SecurityConstraint constraint = new SecurityConstraint();
        constraint.setUserConstraint("CONFIDENTIAL");
        SecurityCollection everything = new SecurityCollection();
        everything.addPattern("/*");
        constraint.addCollection(everything);
        context.addConstraint(constraint);
    }
}
