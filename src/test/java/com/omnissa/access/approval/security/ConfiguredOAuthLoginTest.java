package com.omnissa.access.approval.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The configured deployment: an admin OIDC client exists, so sign-in with
 * Omnissa Access must work exactly as it did before OAuth2 became optional.
 *
 * <p>Making {@code oauth2Login} conditional is the kind of change that can
 * appear to work while quietly dropping one of the details the tenant depends
 * on, and each of those details fails <em>silently</em> in production:
 *
 * <ul>
 *   <li><strong>PKCE.</strong> Access enforces it even for confidential
 *       clients, and Spring sends {@code code_challenge} only for public
 *       clients unless the request resolver is customised. Lose it and every
 *       sign-in is rejected at the tenant.</li>
 *   <li><strong>The {@code group} scope.</strong> Without it Access emits no
 *       group claim, {@link GroupRoleMapper} matches nothing, and every user —
 *       including the administrators — silently becomes a Viewer. Nothing
 *       errors; the tool simply stops letting anyone do anything.</li>
 * </ul>
 *
 * <p>The tenant here is an unreachable hostname and the endpoints are given
 * explicitly, which is the documented Option B fallback. That keeps the suite
 * offline — an {@code issuer-uri} would make the context fetch
 * {@code /.well-known/openid-configuration} at startup — and exercises the
 * manual-provider path at the same time. No test performs a login; the
 * assertions stop at the redirect the browser would be sent, which is where
 * everything above is already visible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:configured-oauth;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Deliberately no spring.mail.host: mail and OAuth2 are independent,
        // and a configured tenant must not drag mail back in as a requirement.
        "omnissa.admin-oauth.client-id=test-client",
        "omnissa.admin-oauth.client-secret=test-secret",
        "omnissa.admin-oauth.redirect-uri=https://approvals.invalid/login/oauth2/code/omnissa",
        "spring.security.oauth2.client.provider.omnissa.authorization-uri="
                + "https://tenant.invalid/SAAS/auth/oauth2/authorize",
        "spring.security.oauth2.client.provider.omnissa.token-uri="
                + "https://tenant.invalid/SAAS/auth/oauthtoken",
        "spring.security.oauth2.client.provider.omnissa.user-info-uri="
                + "https://tenant.invalid/SAAS/jersey/manager/api/userinfo",
        "spring.security.oauth2.client.provider.omnissa.jwk-set-uri="
                + "https://tenant.invalid/SAAS/API/1.0/REST/auth/token",
        "spring.security.oauth2.client.provider.omnissa.user-name-attribute=sub"
})
class ConfiguredOAuthLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @Test
    @DisplayName("a client id and manual endpoints produce the registration the tenant expects")
    void registrationIsBuiltFromTheAdminOAuthProperties() {
        ClientRegistration registration = clientRegistrations.findByRegistrationId("omnissa");

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("test-client");
        assertThat(registration.getAuthorizationGrantType())
                .isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        // The registration id is part of the redirect URI registered on the
        // Access client, so it is not free to change.
        assertThat(registration.getRedirectUri())
                .isEqualTo("https://approvals.invalid/login/oauth2/code/omnissa");
        assertThat(registration.getScopes())
                .contains("openid", "email", "profile", "group");
    }

    @Test
    @DisplayName("the authorization redirect carries PKCE and the group scope")
    void authorizationRequestIsPkceAndAsksForGroups() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/omnissa"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();
        assertThat(location).startsWith("https://tenant.invalid/SAAS/auth/oauth2/authorize");
        assertThat(location).contains("code_challenge=");
        assertThat(location).contains("code_challenge_method=S256");
        assertThat(location).contains("response_type=code");
        // URL-encoded space between scopes.
        assertThat(location).contains("scope=openid%20email%20profile%20group");
    }

    @Test
    @DisplayName("the login page is told OAuth2 is available")
    void loginPageOffersTheOauthButton() throws Exception {
        mockMvc.perform(get("/api/config/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauthEnabled").value(true));
    }

    @Test
    @DisplayName("a chat deep link is saved before the sign-in redirect")
    void deepLinkSurvivesTheLoginRedirect() throws Exception {
        // Slack and Teams approval buttons are links of this shape. The saved
        // request is why defaultSuccessUrl("/") is declared without alwaysUse:
        // an approver arriving from chat has to land on the request they were
        // asked to decide, not the dashboard.
        // Spelled as a real query string rather than .param(...): the saved
        // request is rebuilt from the raw query string, which MockMvc leaves
        // null when parameters are supplied separately.
        var session = mockMvc.perform(get("/requests/42?action=approve"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getRequest()
                .getSession(false);

        assertThat(session).isNotNull();
        var saved = (org.springframework.security.web.savedrequest.SavedRequest)
                session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved).isNotNull();
        assertThat(saved.getRedirectUrl()).contains("/requests/42").contains("action=approve");
    }
}
