package com.omnissa.access.approval.config;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone HTTPS mode's plain port must redirect, not serve.
 *
 * <p>docker-compose-standalone.yml publishes port 80 as "HTTP (redirects to
 * HTTPS automatically)". Before 1.23.5 it did not: the HTTP connector carried
 * a redirect port but nothing told Tomcat that any request had to be
 * confidential, so the login form and every API call were served in the
 * clear to anyone who typed http://. This starts the real connector with the
 * real customizer and asks it.
 */
class SslRedirectTest {

    @Test
    @DisplayName("a request on the plain HTTP connector is redirected to the same path over https")
    void plainHttpIsRedirectedToHttps() throws Exception {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        // https port 8443 is never opened here; only the Location header names it
        new SslRedirectConfig(8443, 0).httpToHttpsRedirect().customize(factory);
        WebServer server = factory.getWebServer();
        server.start();
        try {
            int httpPort = Arrays.stream(((TomcatWebServer) server).getTomcat().getService().findConnectors())
                    .filter(c -> c.getRedirectPort() == 8443)
                    .mapToInt(Connector::getLocalPort)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the additional HTTP connector was not added"));

            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    "http://localhost:" + httpPort + "/queue?page=2").toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            try {
                assertThat(connection.getResponseCode()).isEqualTo(302);
                assertThat(connection.getHeaderField("Location"))
                        .isEqualTo("https://localhost:8443/queue?page=2");
            } finally {
                connection.disconnect();
            }
        } finally {
            server.stop();
        }
    }
}
