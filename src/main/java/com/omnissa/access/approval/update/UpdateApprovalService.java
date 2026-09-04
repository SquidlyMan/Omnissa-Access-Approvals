package com.omnissa.access.approval.update;

import com.omnissa.access.approval.util.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Turns an administrator's approval into a request the host can act on.
 *
 * <p>The application only ever <strong>writes a file</strong>. All Docker
 * privilege stays on the host, where it already is: a systemd path unit
 * watches the control directory, validates the file, rewrites the compose pin,
 * pulls, recreates, and verifies. Nothing here can restart the container —
 * that would need the Docker socket, the privilege trade already rejected for
 * Watchtower.
 *
 * <p>Two ordering rules are load-bearing:
 * <ol>
 *   <li>The audit row is written <em>and committed</em> before the file. A
 *       restart follows within seconds of the file appearing; an approval that
 *       deployed but left no trace of who asked is the worst of both.</li>
 *   <li>Every validation happens before either. The regex proves shape, not
 *       existence — {@code 1.99.0} passes it, the pull fails, and the host is
 *       left with a rewritten compose and a stopped container. The target must
 *       be a version the registry actually listed on the last check.</li>
 * </ol>
 *
 * <p>The control directory is a <em>separate</em> mount from {@code /app/data},
 * which backup archives: a stale request file inside a restored archive would
 * otherwise trigger a deploy on the next tick.
 */
@Service
public class UpdateApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(UpdateApprovalService.class);

    public static final String INTENT_FILE = "update-requested";
    /** The intent file, renamed by the host the moment it starts working on it. */
    public static final String APPLYING_FILE = "update-applying";
    /** Second line of the intent file when a below-floor rollback was typed to confirm. */
    public static final String CONFIRMED_MARKER = "confirmed=below-floor";
    public static final String AUDIT_ACTION = "update-approved";
    /** Written when the audit row exists but the request could not be — so the trail never shows a lone approval. */
    public static final String AUDIT_ACTION_FAILED = "update-approval-failed";
    public static final String AUDIT_ACTION_DISMISSED = "update-result-dismissed";
    /**
     * A request the host has not touched in this long is not going to be
     * touched: the updater is not installed, or the mount is wrong. Refusing
     * every later approval until someone deletes the file by hand would turn a
     * misconfiguration into a lock-out, so a stale request may be replaced.
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final UpdateCheckService checks;
    private final AuditService audit;
    private final AppVersion appVersion;
    private final Path controlDir;

    public UpdateApprovalService(UpdateCheckService checks,
                                 AuditService audit,
                                 AppVersion appVersion,
                                 @Value("${omnissa.update.control-dir:/app/control}") String controlDir) {
        this.checks = checks;
        this.audit = audit;
        this.appVersion = appVersion;
        this.controlDir = Path.of(controlDir);
    }

    /** The outcome the console shows. {@code phase} is {@code requested} or {@code applying}. */
    public record Approval(String target, String previous, Date requestedAt, String phase) {
    }

    /** Thrown for a target that must not be deployed as asked. Carries what to tell the administrator. */
    public static class Refused extends RuntimeException {
        private final boolean confirmationRequired;
        private final List<String> reopened;

        public Refused(String message) {
            this(message, false, List.of());
        }

        public Refused(String message, boolean confirmationRequired, List<String> reopened) {
            super(message);
            this.confirmationRequired = confirmationRequired;
            this.reopened = reopened;
        }

        public boolean confirmationRequired() { return confirmationRequired; }
        public List<String> reopened() { return reopened; }
    }

    /** Thrown when the host side is not wired up — the control directory is not mounted. */
    public static class NotDeployable extends RuntimeException {
        public NotDeployable(String message) { super(message); }
    }

    /**
     * @param target       the version to deploy, exactly as the registry lists it
     * @param confirmation for a target below the floor, the target typed again
     * @param actor        the administrator, for the audit row
     */
    public synchronized Approval approve(String target, String confirmation, String actor) {
        Semver wanted = Semver.parse(target)
                .orElseThrow(() -> new Refused("Not a release version: '" + target + "'. Expected N.N.N."));

        UpdateSnapshot snapshot = checks.current();
        if (!snapshot.knownVersions().contains(wanted.toString())) {
            throw new Refused("Version " + wanted + " is not one the registry listed on the last check. "
                    + "Run Check now, then choose from what it found.");
        }

        String previous = appVersion.current();
        if (wanted.toString().equals(previous)) {
            throw new Refused("Version " + wanted + " is already running.");
        }

        // One deploy at a time. A second request written while the host is
        // working would either be eaten by the first run's clean-up or fire a
        // second deploy seconds after the first — neither is what anyone meant.
        Optional<Approval> inFlight = pending();
        if (inFlight.isPresent()) {
            Approval p = inFlight.get();
            boolean stale = p.requestedAt().toInstant().isBefore(Instant.now().minus(STALE_AFTER));
            if (!stale) {
                throw new Refused("A deployment of " + p.target() + " is already "
                        + ("applying".equals(p.phase()) ? "being applied" : "pending")
                        + ". Wait for the host to finish.");
            }
            logger.warn("Replacing a request for {} that the host has not picked up since {} — is the updater installed?",
                    p.target(), p.requestedAt());
        }

        if (RollbackFloor.isBelowFloor(wanted) && !wanted.toString().equals(confirmation)) {
            throw new Refused("Version " + wanted + " is below the rollback floor (" + RollbackFloor.FLOOR
                    + "). Type the version exactly to confirm.", true, RollbackFloor.reopenedBy(wanted));
        }

        if (!Files.isDirectory(controlDir)) {
            throw new NotDeployable("The control directory " + controlDir + " is not mounted, so the host-side "
                    + "updater cannot see approvals. Add the mount to the compose file — see the deployment "
                    + "guide — and restart.");
        }

        // 1. The trail, committed. If this throws, nothing below happens.
        audit.recordOrThrow(AUDIT_ACTION, "update", wanted.toString(),
                "Approved deployment of " + wanted + " (running " + previous + ")"
                        + (RollbackFloor.isBelowFloor(wanted) ? " — rollback below the floor, confirmed by typing" : ""),
                actor);

        // 2. The request the host acts on. Written to a uniquely named sibling
        //    and renamed so the watcher never reads a half-written file, and so
        //    two approvals racing past the check above cannot share a temp
        //    file. The typed confirmation travels with the request: the host
        //    enforces the same floor and needs to know it was overridden.
        Path intent = controlDir.resolve(INTENT_FILE);
        boolean confirmedBelowFloor = RollbackFloor.isBelowFloor(wanted);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(controlDir, INTENT_FILE + ".", ".tmp");
            Files.writeString(tmp, wanted + "\n" + (confirmedBelowFloor ? CONFIRMED_MARKER + "\n" : ""),
                    StandardCharsets.UTF_8);
            Files.move(tmp, intent, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
            // The approval row is committed and true — the administrator did
            // approve. What did not happen must be in the trail too, through
            // the tolerant path so that a second failure cannot hide the first.
            audit.record(AUDIT_ACTION_FAILED, "update", wanted.toString(),
                    "Approved " + wanted + " but the request could not be written to " + controlDir + ": " + e.getMessage(),
                    actor);
            throw new NotDeployable("Could not write " + intent + ": " + e.getMessage());
        }

        logger.info("Deployment of {} approved by {} (running {}); intent written to {}", wanted, actor, previous, intent);
        return new Approval(wanted.toString(), previous, new Date(), "requested");
    }

    /**
     * A request the host has not finished with, if any: still waiting
     * ({@code update-requested}) or being applied ({@code update-applying}).
     * Only the first line is the target; a confirmation marker may follow it.
     */
    public Optional<Approval> pending() {
        for (String name : List.of(INTENT_FILE, APPLYING_FILE)) {
            Path file = controlDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String target = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                        .findFirst().orElse("").trim();
                FileTime written = Files.getLastModifiedTime(file);
                return Optional.of(new Approval(target, appVersion.current(), new Date(written.toMillis()),
                        name.equals(INTENT_FILE) ? "requested" : "applying"));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public boolean controlDirectoryMounted() {
        return Files.isDirectory(controlDir);
    }

    /** The host's verdict on the last approval, if it has reported one. */
    public Optional<UpdateResult> lastResult() {
        return UpdateResult.read(controlDir);
    }

    /**
     * Remove the host's verdict from the Dashboard. A failure verdict has no
     * expiry of its own — the operator who fixed the problem by hand on an
     * older host would otherwise look at a red box for ever.
     *
     * @return whether there was anything to dismiss
     */
    public boolean dismissResult(String actor) {
        Optional<UpdateResult> current = lastResult();
        if (current.isEmpty()) {
            return false;
        }
        try {
            Files.deleteIfExists(controlDir.resolve(UpdateResult.FILE));
        } catch (IOException e) {
            throw new NotDeployable("Could not remove " + UpdateResult.FILE + ": " + e.getMessage());
        }
        UpdateResult r = current.get();
        audit.record(AUDIT_ACTION_DISMISSED, "update", r.target() != null ? r.target() : "?",
                "Dismissed the host's verdict: " + r.outcome() + (r.reason() != null ? " — " + r.reason() : ""), actor);
        return true;
    }
}
