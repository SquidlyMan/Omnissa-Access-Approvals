package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may do what on /api/updates (#83, acceptance criterion 9). Deploying an
 * image is the most privileged act in the tool — more than deleting a
 * request — so the check and the approval are admin-only, while the banner
 * is for everyone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:update-roles;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url=",
        "omnissa.update.check-enabled=false"
})
class UpdateEndpointRolesTest {

    @Autowired
    private MockMvc mockMvc;

    /** Never let a test reach the real registry. */
    @MockitoBean
    private RegistryClient registry;

    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("a Viewer can read the banner state")
    void viewerReadsStatus() throws Exception {
        mockMvc.perform(get("/api/updates/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detection.runningVersion").exists())
                .andExpect(jsonPath("$.rollbackFloor").value("1.19.5"));
    }

    @Test
    @WithMockUser(roles = "APPROVER")
    @DisplayName("an Approver cannot run a check")
    void approverCannotCheck() throws Exception {
        mockMvc.perform(post("/api/updates/check").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "APPROVER")
    @DisplayName("an Approver cannot approve a deployment")
    void approverCannotApprove() throws Exception {
        mockMvc.perform(post("/api/updates/approve").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"1.22.0\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an Admin can run a check, and the registry is asked with the large page")
    void adminChecks() throws Exception {
        when(registry.repository()).thenReturn("example/app");
        when(registry.listTags()).thenReturn(List.of("1.21.1", "1.22.0", "latest"));
        mockMvc.perform(post("/api/updates/check").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detection.newestVersion").value("1.22.0"))
                .andExpect(jsonPath("$.knownVersions[0]").value("1.22.0"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an Admin approving junk gets a 400 that says why — never a 500")
    void adminBadTargetIs400() throws Exception {
        mockMvc.perform(post("/api/updates/approve").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"latest\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.confirmationRequired").value(false));
    }
}
