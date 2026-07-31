package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.ApprovalsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The callout endpoint must tolerate the same request arriving twice.
 *
 * <p>Omnissa Access delivers each callout from more than one node: two POSTs
 * carrying the same {@code requestId} were observed arriving 25ms apart from
 * different egress addresses, and both were ingested. That is ordinary
 * at-least-once delivery — the sender guarantees arrival and leaves duplicate
 * suppression to the receiver — so storing the second copy was our defect.
 *
 * <p>The consequence was not a tidy extra row. {@code findByRequestId} returned a
 * single entity, so two matching rows threw
 * {@code IncorrectResultSizeDataAccessException} and every one of its sixteen
 * call sites started returning 500: the request could not be opened, decided,
 * swept or pulled.
 *
 * <p>It was invisible until callout authentication began working. While one
 * delivery leg was rejected with a 401, only one copy ever reached the database
 * — a broken handshake was accidentally deduplicating the queue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:duplicate-callout;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class DuplicateCalloutTest {

    private static final String REQUEST_ID = "7ba57285-de3a-4b02-be87-f7a6866d2855";

    private static final String CALLOUT = """
            {"operation":"activation","requestId":"%s","userName":"dean@flaming.ws",
             "resourceName":"I Am Showcase (Access)","resourceType":"WEB_APP"}
            """.formatted(REQUEST_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalsRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private void deliver() throws Exception {
        mockMvc.perform(post("/api/approvals/new")
                        .contentType(MediaType.valueOf(
                                "application/vnd.vmware.horizon.manager.messaging.message+json"))
                        .content(CALLOUT))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("the same callout delivered twice yields one record")
    void duplicateDeliveryStoresOneRecord() throws Exception {
        deliver();
        deliver();

        assertThat(repository.findAllByRequestIdOrderByIdAsc(REQUEST_ID))
                .as("Access delivers from multiple nodes; the second copy must be acknowledged "
                        + "and discarded, not stored")
                .hasSize(1);
    }

    @Test
    @WithMockUser
    @DisplayName("a duplicate is answered 200, so Access does not retry harder")
    void duplicateIsAcknowledged() throws Exception {
        deliver();
        // Answering anything else tells the sender delivery failed, and an
        // at-least-once sender responds by sending more copies.
        deliver();
    }

    @Test
    @WithMockUser
    @DisplayName("lookups survive duplicates already in the database")
    void lookupToleratesExistingDuplicates() {
        // The state this deployment was actually left in: two rows already
        // stored before the fix existed. The lookup must not throw, or the
        // request stays unopenable and undecidable until someone edits the
        // database by hand.
        for (int i = 0; i < 2; i++) {
            // Fields are final, so the JsonCreator constructor is the only way in.
            CalloutRequest row = new CalloutRequest(
                    com.omnissa.access.approval.model.CalloutOperation.activation,
                    REQUEST_ID, "uuid-1", "I Am Showcase (Access)", "dean@flaming.ws",
                    null, null, null, null, null, null);
            row.setState("pending");
            repository.save(row);
        }

        assertThat(repository.findAllByRequestIdOrderByIdAsc(REQUEST_ID)).hasSize(2);
        CalloutRequest found = repository.findByRequestId(REQUEST_ID);
        assertThat(found)
                .as("must return a row rather than throwing IncorrectResultSizeDataAccessException")
                .isNotNull();
        assertThat(found.getId())
                .as("the earliest row, so behaviour does not depend on scan order")
                .isEqualTo(repository.findAllByRequestIdOrderByIdAsc(REQUEST_ID).get(0).getId());
    }
}
