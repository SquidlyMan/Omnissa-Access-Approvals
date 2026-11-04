package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.DecisionOutcome;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import com.omnissa.access.approval.service.DecisionService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Approve all" and "Reject all" decide each request the way a single
 * decision does. Until 1.23.5 the bulk action only posted the response to
 * Access and skipped {@link DecisionService}: no exclusion lifted, no SCIM id
 * captured, so a bulk approval of a default-excluded app showed "approved"
 * while the user still could not see it. Chained requests are still skipped —
 * a stage's approver is a specific person or group, not whoever clicked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bulk-decision;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class BulkDecisionTest {

    private static final MediaType CALLOUT =
            MediaType.valueOf("application/vnd.vmware.horizon.manager.approval.message+json");

    private static String callout(String requestId) {
        return """
                {"operation":"activation","requestId":"%s","userName":"dean@flaming.ws",
                 "resourceName":"Payroll","resourceType":"WEB_APP"}
                """.formatted(requestId);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalsRepository repository;

    @MockitoBean
    DecisionService decisionService;

    @MockitoBean
    RegistryClient registryClient;

    @BeforeEach
    void seed() throws Exception {
        repository.deleteAll();
        mockMvc.perform(post("/api/approvals/new").contentType(CALLOUT).content(callout("plain-1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/approvals/new").contentType(CALLOUT).content(callout("chained-1")))
                .andExpect(status().isOk());
        CalloutRequest chained = repository.findByRequestId("chained-1");
        chained.setChainId(99L);
        repository.save(chained);
        when(decisionService.decide(anyString(), anyBoolean(), anyString(), any(), any(), anyString()))
                .thenReturn(DecisionOutcome.DELIVERED);
    }

    @Test
    @WithMockUser(username = "approver", roles = "APPROVER")
    @DisplayName("each plain pending request goes through DecisionService; a chained one is left alone")
    void bulkApprovalUsesTheDecisionPath() throws Exception {
        mockMvc.perform(post("/api/approvals/response/all").with(csrf()).param("approved", "true"))
                .andExpect(status().isOk());

        verify(decisionService).decide(eq("plain-1"), eq(true), eq("bulk action"), isNull(), isNull(), eq("approver"));
        verify(decisionService, never()).decide(eq("chained-1"), anyBoolean(), anyString(), any(), any(), anyString());
    }
}
