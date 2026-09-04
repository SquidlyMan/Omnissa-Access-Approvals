package com.omnissa.access.approval.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the host-side updater reported after the last approval — the file it
 * writes beside the intent file when it has finished, one {@code key=value}
 * per line. {@code outcome} is {@code deployed}, {@code rolled-back},
 * {@code failed} or {@code refused}; the rest are what the host measured.
 *
 * <p>This is the console's only window onto a deploy that did not stick. The
 * container that comes back after a rollback is the old one, running the old
 * version, and nothing in its own state says anything happened; the host has
 * to tell it. A missing or unreadable file means "nothing to report".
 */
public record UpdateResult(String outcome, String target, String reason, String digest, String version, Date at) {

    public static final String FILE = "update-result";

    public boolean succeeded() {
        return "deployed".equals(outcome);
    }

    public static Optional<UpdateResult> read(Path controlDir) {
        Path file = controlDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Map<String, String> kv = new HashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            String outcome = kv.get("outcome");
            if (outcome == null || outcome.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new UpdateResult(outcome, blankToNull(kv.get("target")), blankToNull(kv.get("reason")),
                    blankToNull(kv.get("digest")), blankToNull(kv.get("version")), parseWhen(kv.get("at"), file)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Date parseWhen(String at, Path file) throws IOException {
        if (at != null && !at.isBlank()) {
            try {
                return Date.from(Instant.parse(at));
            } catch (DateTimeParseException ignored) {
                // fall through to the file's own timestamp
            }
        }
        return new Date(Files.getLastModifiedTime(file).toMillis());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
