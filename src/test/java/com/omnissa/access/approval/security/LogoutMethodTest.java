package com.omnissa.access.approval.security;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-out is a POST with the CSRF token. Until 1.23.5 {@code /logout}
 * answered any method, so a GET from a third-party page — an image tag was
 * enough — ended an approver's session mid-decision.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:logout-method;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class LogoutMethodTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    RegistryClient registryClient;

    @Test
    @DisplayName("a GET to /logout is not a sign-out")
    void getIsNotASignOut() throws Exception {
        MvcResult result = mockMvc.perform(get("/logout")
                        .with(user("viewer").roles("VIEWER"))
                        .header("Accept", "text/html"))
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .as("no logout redirect for a GET")
                .isNotEqualTo("/login?logout=true");
    }

    @Test
    @DisplayName("a POST without the CSRF token is refused")
    void postWithoutCsrfIsRefused() throws Exception {
        mockMvc.perform(post("/logout").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST with the CSRF token signs out")
    void postWithCsrfSignsOut() throws Exception {
        mockMvc.perform(post("/logout").with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout=true"));
    }
}
