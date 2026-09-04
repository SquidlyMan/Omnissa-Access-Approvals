package com.omnissa.access.approval.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Finds out whether a newer release has been published, and remembers the
 * answer.
 *
 * <p>Detection only. Nothing here installs anything: the approval step and the
 * host-side deploy are separate, and deliberately so — the container cannot
 * restart itself without being handed the Docker socket, which is the
 * privilege trade this feature exists to avoid.
 *
 * <p><strong>Fails soft.</strong> A registry outage is logged and recorded on
 * the status row as {@code lastError}; the previous answer stays visible with
 * its timestamp. The console must never show an error page because a third
 * party was slow, and the check must never throw into its scheduler.
 */
@Service
public class UpdateCheckService {

    private static final Logger logger = LoggerFactory.getLogger(UpdateCheckService.class);

    private final RegistryClient registry;
    private final UpdateStatusRepository repository;
    private final AppVersion appVersion;
    private final UpdateNotifier notifier;
    private final boolean enabled;
    private final String checkInterval;
    /** One check at a time, whether from the scheduler or the button — the status row is a singleton. */
    private final ReentrantLock inFlight = new ReentrantLock();

    /** Matches {@code UpdateStatus.lastError}'s column; a longer message would fail the save. */
    static final int MAX_ERROR_LENGTH = 500;

    public UpdateCheckService(RegistryClient registry,
                              UpdateStatusRepository repository,
                              AppVersion appVersion,
                              UpdateNotifier notifier,
                              @Value("${omnissa.update.check-enabled:true}") boolean enabled,
                              @Value("${omnissa.update.check-interval:P1D}") String checkInterval) {
        this.registry = registry;
        this.repository = repository;
        this.appVersion = appVersion;
        this.notifier = notifier;
        this.enabled = enabled;
        this.checkInterval = checkInterval;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Query the registry now and persist what it said. Safe to call from a
     * scheduler or a button; never throws.
     */
    public UpdateSnapshot check() {
        // "Check now" runs on a request thread, the scheduler on its own; two
        // of them interleaving load→save on the singleton row would lose one
        // side's writes — including the once-only announcement stamp. The
        // second caller gets the last-known result rather than a queue.
        if (!inFlight.tryLock()) {
            logger.info("An update check is already running; returning the last-known result");
            return current();
        }
        try {
            return doCheck();
        } finally {
            inFlight.unlock();
        }
    }

    private UpdateSnapshot doCheck() {
        UpdateStatus status = load();
        String running = appVersion.current();

        try {
            List<String> releases = registry.listTags().stream()
                    .filter(Semver::isRelease)
                    .map(tag -> Semver.parse(tag).orElseThrow())
                    .sorted(Comparator.reverseOrder())
                    .map(Semver::toString)
                    .toList();

            status.setLastCheckedAt(new Date());
            if (releases.isEmpty()) {
                // A registry that lists nothing is not a registry with nothing
                // in it — it is an incident, a wrong repository, or a page of
                // moving tags. Overwriting the known list with nothing would
                // refuse every approval, rollback included, until it recovers.
                status.setLastError("The registry listed no release versions (N.N.N); keeping the previous list");
                logger.warn("Update check against {}: no release tags in the response — keeping the last-known list",
                        registry.repository());
            } else {
                status.setLastError(null);
                status.setKnownVersions(String.join(",", releases));
                status.setNewestVersion(releases.get(0));
                announceIfNew(status, running);
            }
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            status.setLastCheckedAt(new Date());
            status.setLastError(truncate(reason));
            logger.warn("Update check against {} failed — keeping the last-known result: {}",
                    registry.repository(), reason);
        }

        try {
            repository.save(status);
        } catch (Exception e) {
            // Never out of here: the scheduler would log and retry identically
            // for ever, and the button would 500. What was measured is still
            // returned for this call; the next check tries the save again.
            logger.error("Could not persist the update status; the last-known result on the Dashboard is stale: {}",
                    e.getMessage());
        }
        return snapshot(status, running);
    }

    static String truncate(String reason) {
        return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    /** The last-known state, without touching the registry. */
    public UpdateSnapshot current() {
        return snapshot(load(), appVersion.current());
    }

    private void announceIfNew(UpdateStatus status, String running) {
        Optional<Semver> newest = Semver.parse(status.getNewestVersion());
        Optional<Semver> current = Semver.parse(running);
        if (newest.isEmpty() || current.isEmpty() || !newest.get().isNewerThan(current.get())) {
            return;
        }
        String candidate = newest.get().toString();
        if (candidate.equals(status.getLastNotifiedVersion())) {
            return; // already announced; a restart must not repeat it
        }
        boolean delivered;
        try {
            delivered = notifier.updateAvailable(running, candidate);
        } catch (Exception e) {
            logger.warn("Update notification for {} failed; will retry on the next check: {}",
                    candidate, e.getMessage());
            return;
        }
        if (delivered) {
            status.setLastNotifiedVersion(candidate);
        }
    }

    private UpdateStatus load() {
        return repository.findById(UpdateStatus.SINGLETON_ID).orElseGet(UpdateStatus::new);
    }

    private UpdateSnapshot snapshot(UpdateStatus status, String running) {
        Optional<Semver> newest = Semver.parse(status.getNewestVersion());
        Optional<Semver> current = Semver.parse(running);
        boolean available = newest.isPresent() && current.isPresent() && newest.get().isNewerThan(current.get());
        List<String> known = status.getKnownVersions() == null || status.getKnownVersions().isBlank()
                ? List.of()
                : List.of(status.getKnownVersions().split(","));
        return new UpdateSnapshot(enabled, checkInterval, running, status.getNewestVersion(), available,
                status.getLastCheckedAt(), status.getLastError(), known);
    }
}
