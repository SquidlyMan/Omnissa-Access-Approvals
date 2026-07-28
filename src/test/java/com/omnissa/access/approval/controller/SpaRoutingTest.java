package com.omnissa.access.approval.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the boundary between "page the SPA owns" and "endpoint the server
 * owns".
 *
 * <p>The interesting test is {@link #routeTheServerHasNeverHeardOf()}: it asks
 * for a path that exists nowhere in this repository — not in the router, not in
 * a mapping, not in a list — and requires the SPA shell back. A test that
 * merely asserted today's set of routes would have passed happily while
 * {@code /users} 404d on refresh, because the bug was precisely that the set
 * was incomplete. Only an unknown route can prove the server no longer needs
 * telling.
 *
 * <p>The rest pin the other side of the boundary: the paths that must keep
 * their own answers, whatever the fallback does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // In-memory, so the suite neither reads nor writes the deployment's
        // ./data H2 files.
        "spring.datasource.url=jdbc:h2:mem:spa-routing;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SpaRoutingTest {

    /**
     * Puts these routing rules under the OAuth2-configured filter chain, which
     * is the arrangement the live deployment runs and the one where
     * {@code /oauth2/**} and {@code /login/oauth2/**} are consumed before MVC
     * sees them. (The unconfigured chain is covered by
     * {@code FirstRunStartupTest}.)
     *
     * <p>Supplying a repository directly takes Boot's auto-configured one out
     * of the picture, which keeps the suite offline: the registration is
     * spelled out endpoint by endpoint, where an {@code issuer-uri} would send
     * the context to fetch {@code /.well-known/openid-configuration} at startup
     * and make every test depend on a reachable tenant. No test performs a
     * login.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubTenant {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(
                    ClientRegistration.withRegistrationId("omnissa")
                            .clientId("spa-routing-test")
                            .clientSecret("spa-routing-test")
                            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                            .scope("openid")
                            .authorizationUri("https://tenant.invalid/SAAS/auth/oauth2/authorize")
                            .tokenUri("https://tenant.invalid/SAAS/auth/oauthtoken")
                            .userInfoUri("https://tenant.invalid/SAAS/auth/userinfo")
                            .jwkSetUri("https://tenant.invalid/SAAS/auth/jwks")
                            .userNameAttributeName("sub")
                            .clientName("Omnissa Access")
                            .build());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a client route the server has never been told about still serves the SPA")
    @WithMockUser(roles = "ADMIN")
    void routeTheServerHasNeverHeardOf() throws Exception {
        mockMvc.perform(get("/a-page-invented-by-this-test"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        // Nested too — a future /settings/notifications must not need a backend
        // change either. (SpaController used to forward /settings/** to a route
        // App.tsx never had; the duplication drifted in both directions.)
        mockMvc.perform(get("/settings/notifications"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/dashboard", "/queue", "/rules", "/users", "/help",
            "/requests/42"})
    @DisplayName("every route App.tsx declares survives a direct GET")
    @WithMockUser(roles = "ADMIN")
    void routesInTheRouter(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("a chat approval deep link resolves, query string and all")
    @WithMockUser(roles = "APPROVER")
    void chatDeepLink() throws Exception {
        // Slack and Teams buttons are links of exactly this shape. A 404 here
        // breaks approvals from chat without breaking anything visible in-app.
        mockMvc.perform(get("/requests/42").param("action", "approve"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/not-a-real-endpoint", "/api/approvals/not-a-real-endpoint"})
    @DisplayName("an unknown API path 404s rather than answering with the SPA")
    @WithMockUser(roles = "ADMIN")
    void unknownApiPathsAreNotTheSpa(String path) throws Exception {
        // The SPA checks res.ok. An HTML shell returned with status 200 reads as
        // a successful, empty response, which is the failure mode the JSON
        // error handling in SecurityConfig exists to prevent.
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    @DisplayName("an unauthenticated API call still gets JSON 401, not a login page")
    void unauthenticatedApiCallGetsJson() throws Exception {
        mockMvc.perform(get("/api/approvals/requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("/actuator/health answers the probe, unauthenticated, and is never forwarded")
    void actuatorHealthIsUntouched() throws Exception {
        // Docker, CasaOS and the UAG monitor consume this. CasaOS recreates the
        // container when it fails, so a fallback that swallowed it would take
        // the service down rather than merely mis-route a page. Asserting the
        // shape rather than UP keeps the test about routing: the tenant is
        // unreachable in a test run, so the aggregate status is not fixed.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(forwardedUrl(null))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("a missing static asset 404s instead of being papered over with the shell")
    @WithMockUser(roles = "ADMIN")
    void missingAssetsAreNotTheSpa() throws Exception {
        // Returning index.html for a missing bundle turns a broken build into a
        // blank page whose console error names neither the file nor the cause.
        mockMvc.perform(get("/assets/index-deadbeef.js"))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
        mockMvc.perform(get("/no-such-icon.png"))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    @DisplayName("a POST to an unknown path is not answered with a page")
    @WithMockUser(roles = "ADMIN")
    void postsAreNotForwarded() throws Exception {
        mockMvc.perform(post("/a-page-invented-by-this-test").with(csrf()))
                .andExpect(forwardedUrl(null));
    }
}
