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

    @Test
    void firstOccurrenceOfAKeyWins() throws Exception {
        // The host writes keys in a fixed order; a value that somehow carried a
        // second "version=" line must not redefine what the host measured.
        Files.writeString(control.resolve(UpdateResult.FILE),
                "outcome=deployed\ntarget=1.22.0\nversion=1.22.0\nreason=x\nversion=9.9.9\noutcome=rolled-back\n");
        UpdateResult r = UpdateResult.read(control).orElseThrow();
        assertEquals("deployed", r.outcome());
        assertEquals("1.22.0", r.version());
    }

    @Test
    void extremeTimestampFallsBackToTheFile() throws Exception {
        // A valid ISO instant that Date cannot hold must not 500 every status call.
        Files.writeString(control.resolve(UpdateResult.FILE), "outcome=deployed\nat=+1000000000-01-01T00:00:00Z\n");
        assertNotNull(UpdateResult.read(control).orElseThrow().at());
    }
}
