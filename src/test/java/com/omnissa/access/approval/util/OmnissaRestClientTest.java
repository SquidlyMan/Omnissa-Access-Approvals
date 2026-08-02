package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.OmnissaServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every real call site constructs a fresh {@link OmnissaRestClient} per call —
 * there is no shared instance — so these tests exist to prove the cache
 * actually works <em>across</em> instances, which is the only way it helps.
 */
class OmnissaRestClientTest {

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private HttpServer startFixture(AtomicInteger tokenCalls, String expiresInField) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/SAAS/auth/oauthtoken", exchange -> {
            int n = tokenCalls.incrementAndGet();
            respond(exchange, "{\"access_token\":\"token-" + n + "\"" + expiresInField + "}");
        });
        server.createContext("/api/probe", exchange -> respond(exchange, "{\"ok\":true}"));
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** A distinct client id per test keeps the process-wide cache from bleeding between tests. */
    private OmnissaServer serverFor(int port, String clientIdSuffix) {
        OmnissaServer server = new OmnissaServer();
        server.setUrl("localhost:" + port);
        server.setClientId("test-client-" + clientIdSuffix);
        server.setClientSecret("secret");
        return server;
    }

    private OmnissaRestClient clientFor(OmnissaServer server, int port) {
        return new OmnissaRestClient(server) {
            @Override
            protected String tokenEndpoint() {
                return "http://localhost:" + port + "/SAAS/auth/oauthtoken";
            }
        };
    }

    @Test
    @DisplayName("a cached token is reused across independently-constructed instances")
    void tokenIsCachedAcrossInstances() throws IOException {
        AtomicInteger tokenCalls = new AtomicInteger();
        httpServer = startFixture(tokenCalls, ",\"expires_in\":3600");
        int port = httpServer.getAddress().getPort();
        OmnissaServer server = serverFor(port, "reuse");
        String probeUrl = "http://localhost:" + port + "/api/probe";

        clientFor(server, port).getForObject(probeUrl, String.class);
        clientFor(server, port).getForObject(probeUrl, String.class);
        clientFor(server, port).getForObject(probeUrl, String.class);

        assertThat(tokenCalls.get())
                .as("three API calls on the same tenant should fetch the token once, not three times")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("checkToken always fetches live, even with a fresh token already cached")
    void checkTokenBypassesTheCache() throws IOException {
        AtomicInteger tokenCalls = new AtomicInteger();
        httpServer = startFixture(tokenCalls, ",\"expires_in\":3600");
        int port = httpServer.getAddress().getPort();
        OmnissaServer server = serverFor(port, "probe-bypass");
        String probeUrl = "http://localhost:" + port + "/api/probe";

        clientFor(server, port).getForObject(probeUrl, String.class);
        assertThat(tokenCalls.get()).isEqualTo(1);

        clientFor(server, port).checkToken();

        assertThat(tokenCalls.get())
                .as("the reachability probe must hit the network every time, or a cached token "
                        + "would report a dead tenant as reachable")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an expired cached token is refetched, not reused past its TTL")
    void expiredTokenIsRefetched() throws IOException, InterruptedException {
        AtomicInteger tokenCalls = new AtomicInteger();
        // expires_in just over the 30s safety margin -> ~1s effective TTL.
        httpServer = startFixture(tokenCalls, ",\"expires_in\":31");
        int port = httpServer.getAddress().getPort();
        OmnissaServer server = serverFor(port, "expiry");
        String probeUrl = "http://localhost:" + port + "/api/probe";

        clientFor(server, port).getForObject(probeUrl, String.class);
        assertThat(tokenCalls.get()).isEqualTo(1);

        Thread.sleep(1100);

        clientFor(server, port).getForObject(probeUrl, String.class);
        assertThat(tokenCalls.get())
                .as("the cached token's TTL elapsed, so this call must fetch a new one")
                .isEqualTo(2);
    }
}
