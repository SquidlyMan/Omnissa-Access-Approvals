package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.ApprovalChain;
import com.omnissa.access.approval.repository.ApprovalChainRepository;
import com.omnissa.access.approval.repository.ApprovalStageRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving a chain's stages must work the second time, and every time after.
 *
 * <p>It did not. {@code deleteByChainId} is a Spring Data <em>derived delete</em>,
 * which requires an active transaction to issue its DELETE. Nothing supplied
 * one, so the outcome depended entirely on whether there was anything to
 * delete: the first save found no existing stages, issued no DELETE, and
 * succeeded — while every save after it hit rows, attempted the DELETE, and
 * failed with a 500. Deleting a chain that had stages failed the same way.
 *
 * <p>The delete-then-reinsert also has to be one unit of work. Without a
 * transaction around the pair, a failure partway through would leave the chain
 * with its old stages gone and its new ones missing — a chain that silently
 * matches nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:chain-stage-rewrite;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class ChainStagesRewriteTest {

    private static final String TWO_STAGES = """
            [{"approverType":"ROLE","approverValue":"ROLE_APPROVER"},
             {"approverType":"ROLE","approverValue":"ROLE_ADMIN"}]""";

    @Autowired private MockMvc mockMvc;
    @Autowired private ApprovalChainRepository chainRepository;
    @Autowired private ApprovalStageRepository stageRepository;

    private Long chainId;

    @BeforeEach
    void seed() {
        stageRepository.deleteAll();
        chainRepository.deleteAll();
        ApprovalChain chain = new ApprovalChain();
        chain.setEnabled(true);
        chain.setName("Rewrite me");
        chain.setAppPattern("Salesforce");
        chainId = chainRepository.save(chain).getId();
    }

    private void saveStages(String body) throws Exception {
        mockMvc.perform(put("/api/chains/" + chainId + "/stages")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("stages can be saved repeatedly — the second save must not 500")
    void savingStagesTwiceSucceeds() throws Exception {
        saveStages(TWO_STAGES);
        assertThat(stageRepository.findByChainIdOrderByStageOrderAsc(chainId)).hasSize(2);

        // The save that used to fail: now there ARE rows to delete.
        saveStages(TWO_STAGES);

        assertThat(stageRepository.findByChainIdOrderByStageOrderAsc(chainId))
                .as("a rewrite replaces the stage list, it does not accumulate duplicates")
                .hasSize(2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a rewrite replaces the previous stages rather than appending to them")
    void rewriteReplacesRatherThanAppends() throws Exception {
        saveStages(TWO_STAGES);
        saveStages("""
                [{"approverType":"GROUP","approverValue":"group-abc"}]""");

        var stages = stageRepository.findByChainIdOrderByStageOrderAsc(chainId);
        assertThat(stages).hasSize(1);
        assertThat(stages.get(0).getApproverType()).isEqualTo("GROUP");
        assertThat(stages.get(0).getApproverValue()).isEqualTo("group-abc");
        assertThat(stages.get(0).getStageOrder())
                .as("order is assigned from array position, so it always starts at 1")
                .isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("deleting a chain that has stages does not 500 and leaves no orphans")
    void deletingChainWithStagesSucceeds() throws Exception {
        saveStages(TWO_STAGES);

        mockMvc.perform(delete("/api/chains/" + chainId).with(csrf()))
                .andExpect(status().isOk());

        assertThat(chainRepository.findById(chainId)).isEmpty();
        assertThat(stageRepository.findByChainIdOrderByStageOrderAsc(chainId))
                .as("stages must go with their chain, not linger pointing at nothing")
                .isEmpty();
    }
}
