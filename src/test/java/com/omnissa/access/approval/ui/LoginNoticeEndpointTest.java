package com.omnissa.access.approval.ui;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public auth-config carries the resolved notice, so the login page renders what the operator set. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "omnissa.ui.login-notice-title=Authorised use only",
        "omnissa.ui.login-notice=All access is logged.\\nBy signing in you consent to monitoring.",
        "omnissa.update.check-enabled=false"
})
class LoginNoticeEndpointTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RegistryClient registryClient;

    @Test
    @DisplayName("an anonymous visitor receives the custom notice with its line break")
    void customNoticeIsPublic() throws Exception {
        mockMvc.perform(get("/api/config/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.kind").value("custom"))
                .andExpect(jsonPath("$.notice.title").value("Authorised use only"))
                .andExpect(jsonPath("$.notice.text").value("All access is logged.\nBy signing in you consent to monitoring."));
    }
}
