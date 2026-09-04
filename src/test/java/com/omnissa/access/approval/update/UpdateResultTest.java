package com.omnissa.access.approval.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UpdateResultTest {

    @TempDir
    Path control;

    @Test
    void absentFileMeansNothingToReport() {
        assertTrue(UpdateResult.read(control).isEmpty());
    }

    @Test
    void readsTheHostsKeyValueFile() throws Exception {
        Files.writeString(control.resolve(UpdateResult.FILE), """
                outcome=rolled-back
                target=1.22.0
                reason=the application reports version '1.21.1', not 1.22.0
                digest=ghcr.io/x@sha256:abc
                version=1.21.1
                at=2026-09-04T10:15:30Z
                """);
        UpdateResult r = UpdateResult.read(control).orElseThrow();
        assertEquals("rolled-back", r.outcome());
        assertFalse(r.succeeded());
        assertEquals("1.22.0", r.target());
        assertEquals("the application reports version '1.21.1', not 1.22.0", r.reason());
        assertEquals("1.21.1", r.version());
        assertEquals(java.time.Instant.parse("2026-09-04T10:15:30Z").toEpochMilli(), r.at().getTime());
    }

    @Test
    void deployedIsTheOnlySuccess() throws Exception {
        Files.writeString(control.resolve(UpdateResult.FILE), "outcome=deployed\ntarget=1.22.0\nversion=1.22.0\n");
        assertTrue(UpdateResult.read(control).orElseThrow().succeeded());
        Files.writeString(control.resolve(UpdateResult.FILE), "outcome=refused\ntarget=1.99.0\n");
        assertFalse(UpdateResult.read(control).orElseThrow().succeeded());
    }

    @Test
    void junkOrMissingOutcomeIsIgnored() throws Exception {
        Files.writeString(control.resolve(UpdateResult.FILE), "target=1.22.0\n");
        assertEquals(Optional.empty(), UpdateResult.read(control));
        Files.writeString(control.resolve(UpdateResult.FILE), "garbage");
        assertEquals(Optional.empty(), UpdateResult.read(control));
    }

    @Test
    void unparseableTimestampFallsBackToTheFile() throws Exception {
        Files.writeString(control.resolve(UpdateResult.FILE), "outcome=deployed\nat=yesterday\n");
        assertNotNull(UpdateResult.read(control).orElseThrow().at());
    }
}
