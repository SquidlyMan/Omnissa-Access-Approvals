package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant service-client secret goes in and never comes back out. Until
 * 1.23.5 {@code GET /api/config/server} — and the echo from POST and PUT —
 * returned it in clear to any administrator, and that client is a Super
 * Admin credential for the tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenant-config-secret;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class TenantConfigSecretTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OmnissaServerRepository repository;

    @MockitoBean
    RegistryClient registryClient;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("the secret is accepted, stored, and never returned")
    void secretIsWriteOnly() throws Exception {
        mockMvc.perform(post("/api/config/server").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"tenant.invalid\",\"clientId\":\"svc\",\"clientSecret\":\"s3cret-value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("svc"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("s3cret-value"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("clientSecret"))));

        mockMvc.perform(get("/api/config/server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("svc"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("clientSecret"))));

        OmnissaServer stored = repository.findAll().get(0);
        assertThat(stored.getClientSecret()).isEqualTo("s3cret-value");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a POST without a secret is refused")
    void postWithoutSecretIsRefused() throws Exception {
        mockMvc.perform(post("/api/config/server").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"tenant.invalid\",\"clientId\":\"svc\"}"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a PUT that leaves the secret blank keeps the stored one; a PUT that names one replaces it")
    void putKeepsTheStoredSecretWhenBlank() throws Exception {
        mockMvc.perform(post("/api/config/server").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"tenant.invalid\",\"clientId\":\"svc\",\"clientSecret\":\"first\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/config/server").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"other.invalid\",\"clientId\":\"svc2\"}"))
                .andExpect(status().isOk());
        OmnissaServer kept = repository.findAll().get(0);
        assertThat(kept.getUrl()).isEqualTo("other.invalid");
        assertThat(kept.getClientId()).isEqualTo("svc2");
        assertThat(kept.getClientSecret()).as("blank means keep").isEqualTo("first");

        mockMvc.perform(put("/api/config/server").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"other.invalid\",\"clientId\":\"svc2\",\"clientSecret\":\"second\"}"))
                .andExpect(status().isOk());
        assertThat(repository.findAll().get(0).getClientSecret()).isEqualTo("second");
    }
}
