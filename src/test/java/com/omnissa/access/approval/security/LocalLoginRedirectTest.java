package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The local sign-in's redirect, end to end through the real filter chain:
 * a deep link saved before the login page comes back as a path on this site,
 * and a sign-in with nothing saved lands on the Dashboard. The companion to
 * {@link PostLoginDestinationTest}, which pins what gets saved; this pins
 * what the success handler does with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-login-redirect;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class LocalLoginRedirectTest {

    private static final String BROWSER_NAVIGATION =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final String PASSWORD = "SomeStr0ngPassword!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    RegistryClient registryClient;

    /**
     * Spied, not mocked: the real throttle runs, and the test only asks
     * whether the handlers told it anything. Until 1.23.5 they never did —
     * {@code defaultSuccessUrl} and {@code failureUrl} had replaced them.
     */
    @MockitoSpyBean
    LoginThrottle loginThrottle;

    @BeforeEach
    void seedViewer() {
        userAccountRepository.deleteAll();
        UserAccount user = new UserAccount();
        user.setUsername("deeplink");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFirstName("Deep");
        user.setLastName("Link");
        user.setEmail("deeplink@example.com");
        user.setEnabled(true);
        Authority viewer = new Authority();
        viewer.setAuthorityName(AuthorityName.ROLE_VIEWER);
        List<Authority> authorities = new ArrayList<>();
        authorities.add(viewer);
        user.setAuthorities(authorities);
        userAccountRepository.save(user);
    }

    @Test
    @DisplayName("a saved deep link comes back as a path on this site, never as an absolute URL")
    void savedDeepLinkIsHonouredAsAPath() throws Exception {
        MvcResult navigation = mockMvc.perform(get("/requests/42?action=approve")
                        .header("Accept", BROWSER_NAVIGATION))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) navigation.getRequest().getSession(false);
        assertThat(session).as("the interrupted navigation was saved in the session").isNotNull();

        MvcResult login = mockMvc.perform(post("/login/local")
                        .session(session)
                        .with(csrf())
                        .param("username", "deeplink")
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String destination = login.getResponse().getRedirectedUrl();
        assertThat(destination)
                .as("the deep link, as a path and query — Spring Security adds its continue marker")
                .startsWith("/requests/42?action=approve")
                .doesNotStartWith("http");
    }

    @Test
    @DisplayName("the handlers are the ones in effect: a failure and a success both reach the throttle")
    void handlersReachTheThrottle() throws Exception {
        mockMvc.perform(post("/login/local")
                        .with(csrf())
                        .param("username", "deeplink")
                        .param("password", "not the password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=true"));
        verify(loginThrottle).recordFailure(anyString(), eq("deeplink"));

        mockMvc.perform(post("/login/local")
                        .with(csrf())
                        .param("username", "deeplink")
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());
        verify(loginThrottle).recordSuccess(anyString(), eq("deeplink"));
    }

    @Test
    @DisplayName("a sign-in with nothing saved lands on the Dashboard")
    void nothingSavedLandsOnTheDashboard() throws Exception {
        mockMvc.perform(post("/login/local")
                        .with(csrf())
                        .param("username", "deeplink")
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
