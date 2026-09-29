package com.omnissa.access.approval.security;

import com.omnissa.access.approval.update.RegistryClient;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins what an unauthenticated request may become after sign-in.
 *
 * <p>Spring Security remembers the request that triggered a login redirect and
 * sends the user back to it once they are in. That is what lets a Slack or
 * Teams deep link survive the round-trip. It is also how a real sign-in ended
 * on a Whitelabel 404: Safari had fetched
 * {@code /apple-touch-icon-precomposed.png} on its own for its Favorites page,
 * the unauthenticated probe was saved as the destination, and "Sign in with
 * Omnissa Access" replayed it with {@code ?continue}. The icon was not even a
 * page. These tests hold the line on both sides: a page navigation is saved, a
 * browser's asset probe never is, and the probe itself needs no session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:post-login-destination;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PostLoginDestinationTest {

    /** Same offline stub tenant as SpaRoutingTest: OAuth2 chain, no login performed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubTenant {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(
                    ClientRegistration.withRegistrationId("omnissa")
                            .clientId("post-login-test")
                            .clientSecret("post-login-test")
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

    private static final String BROWSER_NAVIGATION =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    RegistryClient registryClient;

    @Test
    @DisplayName("a deep link a browser navigates to is saved as the post-login destination")
    void deepLinkIsSaved() throws Exception {
        // The query goes in the URL, as a browser sends it: a saved request is
        // rebuilt from the query string, not from parsed parameters.
        MvcResult result = mockMvc.perform(get("/requests/42?action=approve")
                        .header("Accept", BROWSER_NAVIGATION))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        SavedRequest saved = savedRequest(result);
        assertThat(saved).as("the deep link is what the user should land on after sign-in").isNotNull();
        // Spring Security 6 appends its "continue" marker when it replays a saved
        // request, so the destination is matched on the path and query it carries.
        assertThat(saved.getRedirectUrl()).contains("/requests/42?action=approve");
    }

    @Test
    @DisplayName("Safari's touch-icon probe is served without a session and is never a destination")
    void touchIconProbeNeedsNoSessionAndIsNotSaved() throws Exception {
        MvcResult result = mockMvc.perform(get("/apple-touch-icon-precomposed.png")
                        .header("Accept", "image/*,*/*;q=0.8"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(savedRequest(result)).isNull();
    }

    @ParameterizedTest(name = "{0} with Accept {1}")
    @DisplayName("what is not a page navigation is redirected to login but never saved")
    @CsvSource({
            // an asset the build does not ship: still a file, still not a page
            "/some-browser-probe.png,       image/*",
            // a page path asked for as something other than HTML
            "/queue,                        image/*",
            // a page path with no Accept header at all — a script, not a person
            "/queue,                        ''",
    })
    void nonNavigationIsNotSaved(String path, String accept) throws Exception {
        var request = get(path);
        if (!accept.isEmpty()) {
            request = request.header("Accept", accept);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(savedRequest(result))
                .as("only a page a person navigated to may become where they land after sign-in")
                .isNull();
    }

    @Test
    @DisplayName("an API call is answered with a status, and is never a destination")
    void apiCallIsNotSaved() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests")
                        .header("Accept", "application/json"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(savedRequest(result)).isNull();
    }

    /** Reads back whatever the filter chain stored in the session, the way the login success handlers do. */
    private static SavedRequest savedRequest(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        return session == null ? null
                : new HttpSessionRequestCache().getRequest(result.getRequest(), result.getResponse());
    }
}
