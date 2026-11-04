package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The callout endpoint is the one public POST. Its rate limit counts requests,
 * not bytes, so until 1.23.5 a body of any size was read into memory before
 * anything looked at it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:callout-body-limit;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class CalloutBodyLimitTest {

    private static final MediaType CALLOUT =
            MediaType.valueOf("application/vnd.vmware.horizon.manager.approval.message+json");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    RegistryClient registryClient;

    @Test
    @DisplayName("a body over the limit is refused with 413 before it is parsed")
    void oversizedBodyIsRefused() throws Exception {
        String body = "{\"padding\":\"" + "x".repeat(ApprovalController.MAX_CALLOUT_BODY_BYTES) + "\"}";
        mockMvc.perform(post("/api/approvals/new").contentType(CALLOUT).content(body))
                .andExpect(status().is(413));
    }

    @Test
    @DisplayName("the empty probe Access sends when its settings are saved is still acknowledged")
    void emptyProbeIsAcknowledged() throws Exception {
        mockMvc.perform(post("/api/approvals/new").contentType(CALLOUT))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the log line for an unparseable body is cut short")
    void badBodyLogLineIsAbbreviated() {
        String junk = "y".repeat(5_000);
        assertThat(ApprovalController.abbreviate(junk)).hasSizeLessThan(600).endsWith("[5000 chars]");
        assertThat(ApprovalController.abbreviate("short")).isEqualTo("short");
    }
}
