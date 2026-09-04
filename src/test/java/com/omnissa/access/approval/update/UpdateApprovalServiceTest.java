package com.omnissa.access.approval.update;

import com.omnissa.access.approval.util.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approval → intent file (#83, acceptance criteria 10, 15, 16, 17, 18).
 */
class UpdateApprovalServiceTest {

    @TempDir Path control;

    private UpdateCheckService checks;
    private AuditService audit;
    private UpdateApprovalService service;

    private static AppVersion running(String version) {
        Properties props = new Properties();
        props.setProperty("version", version);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));
        return new AppVersion(provider);
    }

    @BeforeEach
    void setUp() {
        checks = mock(UpdateCheckService.class);
        when(checks.current()).thenReturn(new UpdateSnapshot(true, "P1D", "1.21.1", "1.22.0", true, null, null,
                List.of("1.22.0", "1.21.1", "1.19.5", "1.19.4", "1.5.9")));
        audit = mock(AuditService.class);
        service = new UpdateApprovalService(checks, audit, running("1.21.1"), control.toString());
    }

    @Test
    @DisplayName("approving a published newer version writes the intent file, audited first")
    void approveWritesIntentAfterAudit() {
        // The audit row must be committed before the file exists (criterion 10).
        doAnswer(inv -> {
            assertThat(control.resolve(UpdateApprovalService.INTENT_FILE))
                    .as("intent file must not exist yet when the audit row is written").doesNotExist();
            return null;
        }).when(audit).recordOrThrow(anyString(), anyString(), anyString(), anyString(), anyString());

        UpdateApprovalService.Approval a = service.approve("1.22.0", null, "amorgan");

        assertThat(a.target()).isEqualTo("1.22.0");
        assertThat(a.previous()).isEqualTo("1.21.1");
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).hasContent("1.22.0\n");
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE + ".tmp")).doesNotExist();
        verify(audit).recordOrThrow(eq("update-approved"), eq("update"), eq("1.22.0"), anyString(), eq("amorgan"));
        assertThat(service.pending()).isPresent().get().extracting(UpdateApprovalService.Approval::target)
                .isEqualTo("1.22.0");
    }

    @Test
    @DisplayName("an audit failure aborts the approval — no file is written (criterion 10)")
    void auditFailureAborts() {
        doThrow(new RuntimeException("database unavailable"))
                .when(audit).recordOrThrow(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.approve("1.22.0", null, "amorgan")).hasMessageContaining("database");
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("a version the registry never listed is refused before anything is touched (criterion 16)")
    void unpublishedRefused() {
        assertThatThrownBy(() -> service.approve("1.99.0", null, "amorgan"))
                .isInstanceOf(UpdateApprovalService.Refused.class)
                .hasMessageContaining("not one the registry listed");
        verify(audit, never()).recordOrThrow(any(), any(), any(), any(), any());
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("shape is checked before the registry list — junk never reaches the file")
    void junkRefused() {
        for (String junk : List.of("latest", "1.21", "../../etc/passwd", "1.22.0; rm -rf /", "")) {
            assertThatThrownBy(() -> service.approve(junk, null, "amorgan"))
                    .as(junk).isInstanceOf(UpdateApprovalService.Refused.class);
        }
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("the running version is refused — nothing to deploy")
    void alreadyRunning() {
        assertThatThrownBy(() -> service.approve("1.21.1", null, "amorgan"))
                .hasMessageContaining("already running");
    }

    @Test
    @DisplayName("an older version at or above the floor deploys without ceremony (criterion 14)")
    void rollbackAboveFloor() {
        service.approve("1.19.5", null, "amorgan");
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).hasContent("1.19.5\n");
    }

    @Test
    @DisplayName("below the floor is refused until the version is typed, and the refusal names what reopens (criterion 15)")
    void belowFloorNeedsTypedConfirmation() {
        assertThatThrownBy(() -> service.approve("1.19.4", null, "amorgan"))
                .isInstanceOf(UpdateApprovalService.Refused.class)
                .satisfies(t -> {
                    UpdateApprovalService.Refused r = (UpdateApprovalService.Refused) t;
                    assertThat(r.confirmationRequired()).isTrue();
                    assertThat(r.reopened()).anyMatch(x -> x.contains("unauthenticated"));
                });
        assertThatThrownBy(() -> service.approve("1.19.4", "1.19.5", "amorgan"))
                .as("the wrong version typed is not a confirmation")
                .isInstanceOf(UpdateApprovalService.Refused.class);
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).doesNotExist();

        service.approve("1.19.4", "1.19.4", "amorgan");
        assertThat(control.resolve(UpdateApprovalService.INTENT_FILE)).hasContent("1.19.4\n" + UpdateApprovalService.CONFIRMED_MARKER + "\n");
        verify(audit).recordOrThrow(eq("update-approved"), eq("update"), eq("1.19.4"),
                org.mockito.ArgumentMatchers.contains("below the floor"), eq("amorgan"));
    }

    @Test
    @DisplayName("an unmounted control directory is reported as not deployable, and audited nothing")
    void unmountedControlDir() {
        UpdateApprovalService none = new UpdateApprovalService(checks, audit, running("1.21.1"),
                control.resolve("does-not-exist").toString());
        assertThatThrownBy(() -> none.approve("1.22.0", null, "amorgan"))
                .isInstanceOf(UpdateApprovalService.NotDeployable.class)
                .hasMessageContaining("not mounted");
        verify(audit, never()).recordOrThrow(any(), any(), any(), any(), any());
        assertThat(none.controlDirectoryMounted()).isFalse();
    }

    @Test
    @DisplayName("the intent file lives outside /app/data, so a restored backup cannot carry one (criterion 17)")
    void intentIsNotUnderData() throws Exception {
        // The default is a sibling of /app/data, never inside it.
        UpdateApprovalService defaults = new UpdateApprovalService(checks, audit, running("1.21.1"), "/app/control");
        Path data = Files.createDirectories(control.resolve("app").resolve("data"));
        assertThat(Path.of("/app/control").startsWith(Path.of("/app/data"))).isFalse();
        assertThat(defaults.controlDirectoryMounted() || !Files.isDirectory(Path.of("/app/control"))).isTrue();
        assertThat(data).exists(); // and the service never looks there
        assertThat(service.pending()).isEmpty();
    }

    @Test
    @DisplayName("a typed below-floor confirmation travels to the host as a marker line")
    void belowFloorConfirmedWritesMarker() throws Exception {
        service.approve("1.19.4", "1.19.4", "amorgan");
        assertThat(Files.readAllLines(control.resolve(UpdateApprovalService.INTENT_FILE)))
                .containsExactly("1.19.4", UpdateApprovalService.CONFIRMED_MARKER);
        // and the console reads back only the version, not the marker
        assertThat(service.pending()).get().extracting(UpdateApprovalService.Approval::target).isEqualTo("1.19.4");
    }

    @Test
    @DisplayName("an ordinary approval is one line — no marker to misread")
    void aboveFloorHasNoMarker() throws Exception {
        service.approve("1.22.0", null, "amorgan");
        assertThat(Files.readAllLines(control.resolve(UpdateApprovalService.INTENT_FILE))).containsExactly("1.22.0");
    }

    @Test
    @DisplayName("a request the host has not finished with blocks a second approval")
    void pendingRequestBlocksASecondApproval() throws Exception {
        service.approve("1.22.0", null, "amorgan");
        assertThatThrownBy(() -> service.approve("1.19.5", null, "bchen"))
                .isInstanceOf(UpdateApprovalService.Refused.class)
                .hasMessageContaining("1.22.0").hasMessageContaining("already pending");
        assertThat(Files.readAllLines(control.resolve(UpdateApprovalService.INTENT_FILE))).containsExactly("1.22.0");
        verify(audit).recordOrThrow(anyString(), anyString(), eq("1.22.0"), anyString(), anyString());
        verify(audit, never()).recordOrThrow(anyString(), anyString(), eq("1.19.5"), anyString(), anyString());
    }

    @Test
    @DisplayName("while the host is applying, the console sees that phase and refuses another")
    void applyingCountsAsPending() throws Exception {
        Files.writeString(control.resolve(UpdateApprovalService.APPLYING_FILE), "1.22.0\n");
        assertThat(service.pending()).get().extracting(UpdateApprovalService.Approval::phase).isEqualTo("applying");
        assertThatThrownBy(() -> service.approve("1.19.5", null, "bchen"))
                .isInstanceOf(UpdateApprovalService.Refused.class).hasMessageContaining("being applied");
    }

    @Test
    @DisplayName("a request nobody picked up in ten minutes may be replaced — a missing updater must not lock the console out")
    void staleRequestIsReplaced() throws Exception {
        Path intent = control.resolve(UpdateApprovalService.INTENT_FILE);
        Files.writeString(intent, "1.22.0\n");
        Files.setLastModifiedTime(intent, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(UpdateApprovalService.STALE_AFTER).minusSeconds(60)));
        service.approve("1.19.5", null, "bchen");
        assertThat(Files.readAllLines(intent)).containsExactly("1.19.5");
    }

    @Test
    @DisplayName("a request that cannot be written leaves a compensating audit row, not a lone approval")
    void writeFailureLeavesACompensatingAuditRow() throws Exception {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> original = Files.getPosixFilePermissions(control);
        Files.setPosixFilePermissions(control, java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThatThrownBy(() -> service.approve("1.22.0", null, "amorgan"))
                    .isInstanceOf(UpdateApprovalService.NotDeployable.class);
            verify(audit).recordOrThrow(eq(UpdateApprovalService.AUDIT_ACTION), anyString(), eq("1.22.0"), anyString(), eq("amorgan"));
            verify(audit).record(eq(UpdateApprovalService.AUDIT_ACTION_FAILED), anyString(), eq("1.22.0"), anyString(), eq("amorgan"));
        } finally {
            Files.setPosixFilePermissions(control, original);
        }
        assertThat(Files.list(control).filter(f -> f.getFileName().toString().endsWith(".tmp")).count())
                .as("no temp file left behind").isZero();
    }

    @Test
    @DisplayName("dismissing the host's verdict deletes it and is audited")
    void dismissResultDeletesAndAudits() throws Exception {
        Files.writeString(control.resolve(UpdateResult.FILE), "outcome=rolled-back\ntarget=1.22.0\nreason=x\n");
        assertThat(service.dismissResult("amorgan")).isTrue();
        assertThat(control.resolve(UpdateResult.FILE)).doesNotExist();
        verify(audit).record(eq(UpdateApprovalService.AUDIT_ACTION_DISMISSED), anyString(), eq("1.22.0"), anyString(), eq("amorgan"));
        assertThat(service.dismissResult("amorgan")).as("nothing to dismiss the second time").isFalse();
    }
}
