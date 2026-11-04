package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A live-update stream must not outlive the application that serves it.
 *
 * <p>With Boot's graceful shutdown, an open {@code /api/approvals/stream}
 * — an emitter with no timeout, held for as long as a Dashboard or Queue tab
 * is — kept the JVM waiting out the whole 30 s shutdown phase, and Docker's
 * 10 s stop timeout killed the container first (the ZimaOS 1.8.0 reboot,
 * 2026-10-24). Pinned from both ends: the close handler completes an open
 * stream through the real servlet async path and finishes a late one before
 * it can hold anything, and a real context close reaches that handler. The
 * shared context is marked dirty because the handler is one-way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sse-shutdown;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class SseShutdownTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseController sseController;

    @MockitoBean
    RegistryClient registryClient;

    @Test
    @DisplayName("an open stream is completed through the servlet async path, and a late one at once")
    void openStreamsAreCompletedWhenShutdownBegins() throws Exception {
        MvcResult open = mockMvc.perform(get("/api/approvals/stream")
                        .with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(sseController.openStreams())
                .as("the stream is held open, as a Dashboard tab holds it")
                .isEqualTo(1);

        sseController.closeStreamsForShutdown();

        assertThatNoException()
                .as("the completion reached the servlet layer: the async result was set and dispatched")
                .isThrownBy(() -> open.getAsyncResult(2_000));
        mockMvc.perform(asyncDispatch(open)).andExpect(status().isOk());
        assertThat(sseController.openStreams()).isZero();

        // A tab reconnecting after shutdown began: the emitter is completed
        // before it is returned, so it never joins the open set.
        sseController.stream();
        assertThat(sseController.openStreams()).isZero();
    }

    @Test
    @DisplayName("closing an application context reaches the controller")
    void contextCloseReachesTheController() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SseController.class)) {
            SseController controller = context.getBean(SseController.class);
            controller.stream();
            assertThat(controller.openStreams()).isEqualTo(1);

            context.close();

            assertThat(controller.openStreams())
                    .as("ContextClosedEvent is delivered before the lifecycle beans stop, so the streams are gone first")
                    .isZero();
            controller.stream();
            assertThat(controller.openStreams()).isZero();
        }
    }
}
