package com.omnissa.access.approval.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

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
            status.setLastError(null);
            status.setKnownVersions(String.join(",", releases));
            status.setNewestVersion(releases.isEmpty() ? null : releases.get(0));

            announceIfNew(status, running);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            status.setLastCheckedAt(new Date());
            status.setLastError(reason);
            logger.warn("Update check against {} failed — keeping the last-known result: {}",
                    registry.repository(), reason);
        }

        repository.save(status);
        return snapshot(status, running);
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
