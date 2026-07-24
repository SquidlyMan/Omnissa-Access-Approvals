package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AuditEvent;
import com.omnissa.access.approval.repository.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Decisions that arrive without a Spring session — a Slack approval (#50) — must
 * be audited under the real approver, not "system". The audit trail's admin
 * column is the authoritative "who did this", so falling back to "system" there
 * would lose attribution even though the message text names the approver.
 */
class AuditServiceExplicitActorTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditService auditService = new AuditService();

    AuditServiceExplicitActorTest() {
        ReflectionTestUtils.setField(auditService, "auditEventRepository", repository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuditEvent recorded() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void explicitActorIsRecordedAsTheAdmin() {
        SecurityContextHolder.clearContext(); // no session, as with a Slack callback
        auditService.record("approved", "req-1", "Salesforce",
                "Approved by dean@flaming.ws (via Slack)", "dean@flaming.ws (via Slack)");
        assertThat(recorded().getAdminUsername()).isEqualTo("dean@flaming.ws (via Slack)");
    }

    @Test
    void nullOrBlankActorFallsBackToSecurityContext() {
        SecurityContextHolder.clearContext();
        auditService.record("approved", "req-2", "Salesforce", "msg", null);
        assertThat(recorded().getAdminUsername()).isEqualTo("system");
    }

    @Test
    void legacyFourArgOverloadStillResolvesFromContext() {
        SecurityContextHolder.clearContext();
        auditService.record("request-received", "req-3", "Salesforce", "msg");
        assertThat(recorded().getAdminUsername()).isEqualTo("system");
    }
}
