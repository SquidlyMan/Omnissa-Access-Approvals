package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Design decision D1, asserted where it actually has to hold: over HTTP.
 *
 * <p>A claim is <strong>advisory, never authorization</strong>. Any APPROVER
 * may decide any request, claimed by them, claimed by somebody else, or
 * unclaimed. Making a claim authoritative would make a request undecidable the
 * moment its owner became unavailable — a convenience turned into an outage.
 *
 * <p>This is a controller test rather than a unit test on purpose. The
 * realistic way the guarantee dies is not a deliberate decision but a later,
 * well-meaning change that starts rejecting a decision from anyone but the
 * owner. A unit test on the service would not catch that; a request over the
 * wire does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:claim-not-authz;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class ClaimIsNotAuthorizationTest {

    private static final String REQUEST_ID = "claim-guard-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalsRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        CalloutRequest request = new CalloutRequest(CalloutOperation.activation, REQUEST_ID,
                "uuid-1", "Salesforce", "jdoe", null, null, null, null, null, null);
        request.setState("pending");
        repository.save(request);
    }

    @Test
    @WithMockUser(username = "alice", roles = "APPROVER")
    @DisplayName("an approver can claim a pending request")
    void approverCanClaim() throws Exception {
        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/claim").with(csrf()))
                .andExpect(status().isOk());

        assertThat(repository.findByRequestId(REQUEST_ID).getAssignedOwner()).isEqualTo("alice");
    }

    @Test
    @WithMockUser(username = "bob", roles = "APPROVER")
    @DisplayName("a request claimed by someone else is still decidable by another approver")
    void claimedByOneDecidableByAnother() throws Exception {
        CalloutRequest held = repository.findByRequestId(REQUEST_ID);
        held.setAssignedOwner("alice");
        held.setAssignedAt(new java.util.Date());
        repository.save(held);

        // Bob decides Alice's claimed request. There is no Access tenant
        // configured here, so delivery cannot succeed — but the point is that
        // the request is ACCEPTED and processed rather than refused on the
        // grounds that somebody else holds it.
        mockMvc.perform(post("/api/approvals/response")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + REQUEST_ID + "\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob", roles = "APPROVER")
    @DisplayName("claiming does not steal a claim someone else holds")
    void claimingDoesNotSteal() throws Exception {
        CalloutRequest held = repository.findByRequestId(REQUEST_ID);
        held.setAssignedOwner("alice");
        held.setAssignedAt(new java.util.Date());
        repository.save(held);

        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/claim").with(csrf()))
                .andExpect(status().isConflict());

        assertThat(repository.findByRequestId(REQUEST_ID).getAssignedOwner()).isEqualTo("alice");
    }

    @Test
    @WithMockUser(username = "bob", roles = "APPROVER")
    @DisplayName("any approver may release any claim, so nothing stays welded to someone who left")
    void anyApproverMayRelease() throws Exception {
        CalloutRequest held = repository.findByRequestId(REQUEST_ID);
        held.setAssignedOwner("alice");
        held.setAssignedAt(new java.util.Date());
        repository.save(held);

        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/release").with(csrf()))
                .andExpect(status().isOk());

        assertThat(repository.findByRequestId(REQUEST_ID).getAssignedOwner()).isNull();
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    @DisplayName("a VIEWER cannot claim — the security list must not fail open")
    void viewerCannotClaim() throws Exception {
        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/claim").with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(repository.findByRequestId(REQUEST_ID).getAssignedOwner()).isNull();
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    @DisplayName("a VIEWER cannot escalate either")
    void viewerCannotEscalate() throws Exception {
        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/escalate").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "carol", roles = "APPROVER")
    @DisplayName("assigning to a named approver records them as owner")
    void assignToNamedApprover() throws Exception {
        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/assign")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"dave@corp.com\"}"))
                .andExpect(status().isOk());

        assertThat(repository.findByRequestId(REQUEST_ID).getAssignedOwner()).isEqualTo("dave@corp.com");
    }

    @Test
    @WithMockUser(username = "alice", roles = "APPROVER")
    @DisplayName("a decided request can no longer be claimed")
    void decidedRequestCannotBeClaimed() throws Exception {
        CalloutRequest decided = repository.findByRequestId(REQUEST_ID);
        decided.setState("approved");
        repository.save(decided);

        mockMvc.perform(post("/api/approvals/requests/" + REQUEST_ID + "/claim").with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
