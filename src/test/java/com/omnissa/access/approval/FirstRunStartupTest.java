package com.omnissa.access.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The state every installation is in before anyone has configured anything:
 * no Omnissa Access tenant, no OIDC client, no SMTP relay.
 *
 * <p>This is the one configuration nothing else in the suite exercises, and it
 * was the one that could not start. Two unrelated required dependencies made
 * the shipped defaults unbootable, so the first thing a new operator saw was a
 * stack trace rather than a sign-in page:
 *
 * <ul>
 *   <li>{@code application.properties} always defined
 *       {@code spring.security.oauth2.client.registration.omnissa.client-id},
 *       because a placeholder with an empty default still defines the key. Boot
 *       bound a registration with a blank client id and refused it —
 *       <em>"Client id of registration 'omnissa' must not be empty"</em>.</li>
 *   <li>{@code MailNotification} required a {@link JavaMailSender}, which Boot
 *       only auto-configures when {@code spring.mail.host} is set —
 *       <em>"Field mailSender ... required a bean of type 'JavaMailSender'
 *       that could not be found"</em>.</li>
 * </ul>
 *
 * <p>Nothing here sets either. That is the point: the properties deliberately
 * override only the database, so that what is under test is the shipped
 * defaults rather than a configuration invented by the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // In-memory, so the suite neither reads nor writes the deployment's
        // ./data H2 files. Deliberately the only override: no OAuth2 client,
        // no mail host, nothing else.
        "spring.datasource.url=jdbc:h2:mem:first-run;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FirstRunStartupTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("neither an OAuth2 client nor a mail sender exists, and the context still starts")
    void nothingIsConfiguredAndThatIsFine() {
        // Asserting the beans are absent — rather than just that the context
        // loaded — keeps the test honest if someone later makes one of them
        // unconditional again: a stub or a default would make this pass while
        // reintroducing the requirement it exists to forbid.
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JavaMailSender.class)).isEmpty();
    }

    @Test
    @DisplayName("the login page reports local sign-in only, so the SPA hides the OAuth button")
    void loginPageIsOfferedLocalSignInOnly() throws Exception {
        // The button has to be hidden as well as unwired: offering "Sign in with
        // Omnissa Access" on an install with no tenant sends the operator to a
        // 404 with no explanation of what they were meant to configure.
        mockMvc.perform(get("/api/config/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauthEnabled").value(false))
                .andExpect(jsonPath("$.localLoginDisabled").value(false));
    }

    @Test
    @DisplayName("the local sign-in page is served unauthenticated")
    void localLoginPageIsReachable() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unconfigured OAuth2 login endpoint says 'not here' rather than half-working")
    void oauthEndpointIsAbsent() throws Exception {
        // With no registration there is no OAuth2AuthorizationRequestRedirectFilter,
        // so this falls through to MVC — where SpaController refuses to answer
        // a backend prefix with the SPA shell. A 302 here would mean oauth2Login
        // was wired against a client that does not exist.
        mockMvc.perform(get("/oauth2/authorization/omnissa"))
                .andExpect(status().isNotFound());
    }
}
