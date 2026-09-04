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
    public static final String AUDIT_ACTION = "update-approved";

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

    /** The outcome the console shows. */
    public record Approval(String target, String previous, Date requestedAt) {
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
    public Approval approve(String target, String confirmation, String actor) {
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

        // 2. The request the host acts on. Written to a sibling and renamed so
        //    the watcher never reads a half-written file.
        Path intent = controlDir.resolve(INTENT_FILE);
        try {
            Path tmp = controlDir.resolve(INTENT_FILE + ".tmp");
            Files.writeString(tmp, wanted + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, intent, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new NotDeployable("Could not write " + intent + ": " + e.getMessage());
        }

        logger.info("Deployment of {} approved by {} (running {}); intent written to {}", wanted, actor, previous, intent);
        return new Approval(wanted.toString(), previous, new Date());
    }

    /** A request the host has not yet consumed, if any. */
    public Optional<Approval> pending() {
        Path intent = controlDir.resolve(INTENT_FILE);
        if (!Files.isRegularFile(intent)) {
            return Optional.empty();
        }
        try {
            String target = Files.readString(intent, StandardCharsets.UTF_8).trim();
            FileTime written = Files.getLastModifiedTime(intent);
            return Optional.of(new Approval(target, appVersion.current(), new Date(written.toMillis())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public boolean controlDirectoryMounted() {
        return Files.isDirectory(controlDir);
    }
}
