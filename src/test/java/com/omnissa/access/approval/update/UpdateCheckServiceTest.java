package com.omnissa.access.approval.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Detection and fail-soft behaviour (#83, acceptance criteria 1, 2, 5, 8).
 */
class UpdateCheckServiceTest {

    private static AppVersion running(String version) {
        Properties props = new Properties();
        props.setProperty("version", version);
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));
        return new AppVersion(provider);
    }

    /** One-row repository, in memory. */
    private static UpdateStatusRepository memoryRepo(UpdateStatus seed) {
        AtomicReference<UpdateStatus> row = new AtomicReference<>(seed);
        UpdateStatusRepository repo = mock(UpdateStatusRepository.class);
        when(repo.findById(UpdateStatus.SINGLETON_ID)).thenAnswer(i -> Optional.ofNullable(row.get()));
        when(repo.save(any(UpdateStatus.class))).thenAnswer(i -> { row.set(i.getArgument(0)); return row.get(); });
        return repo;
    }

    private static RegistryClient registryWith(String... tags) {
        RegistryClient registry = mock(RegistryClient.class);
        when(registry.repository()).thenReturn("example/app");
        when(registry.listTags()).thenReturn(List.of(tags));
        return registry;
    }

    /** Records every announcement; delivered=true stamps the version as announced. */
    private static class RecordingNotifier implements UpdateNotifier {
        final List<String> announced = new ArrayList<>();
        boolean delivered = true;
        @Override public boolean updateAvailable(String runningVersion, String newestVersion) {
            announced.add(newestVersion);
            return delivered;
        }
    }

    @Test
    @DisplayName("running 1.21.1 with 1.21.2 published -> update available (criterion 1)")
    void patchDetected() {
        UpdateCheckService svc = new UpdateCheckService(
                registryWith("1.21.1", "1.21.2", "1.21", "latest", "sha-abc"),
                memoryRepo(null), running("1.21.1"), new RecordingNotifier(), true, "P1D");
        UpdateSnapshot s = svc.check();
        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.newestVersion()).isEqualTo("1.21.2");
        assertThat(s.knownVersions()).as("moving and commit tags excluded").containsExactly("1.21.2", "1.21.1");
        assertThat(s.lastError()).isNull();
    }

    @Test
    @DisplayName("running 1.21.1 with 2.0.0 published -> detected (criterion 2)")
    void majorDetected() {
        UpdateCheckService svc = new UpdateCheckService(
                registryWith("1.21.1", "2.0.0"), memoryRepo(null), running("1.21.1"),
                new RecordingNotifier(), true, "P1D");
        assertThat(svc.check().newestVersion()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("up to date when the newest published equals the running version")
    void upToDate() {
        UpdateCheckService svc = new UpdateCheckService(
                registryWith("1.21.1", "1.9.5"), memoryRepo(null), running("1.21.1"),
                new RecordingNotifier(), true, "P1D");
        UpdateSnapshot s = svc.check();
        assertThat(s.updateAvailable()).isFalse();
        assertThat(s.newestVersion()).as("1.9.5 must not win").isEqualTo("1.21.1");
    }

    @Test
    @DisplayName("registry unreachable -> last-known state kept, error recorded, no throw (criterion 5)")
    void failsSoft() {
        UpdateStatus previous = new UpdateStatus();
        previous.setNewestVersion("1.21.2");
        previous.setKnownVersions("1.21.2,1.21.1");
        RegistryClient down = mock(RegistryClient.class);
        when(down.repository()).thenReturn("example/app");
        when(down.listTags()).thenThrow(new IllegalStateException("connect timed out"));

        UpdateCheckService svc = new UpdateCheckService(down, memoryRepo(previous), running("1.21.1"),
                new RecordingNotifier(), true, "P1D");
        UpdateSnapshot s = svc.check();

        assertThat(s.lastError()).contains("connect timed out");
        assertThat(s.newestVersion()).as("previous answer survives the outage").isEqualTo("1.21.2");
        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.lastCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("announces a new version once; a second check — or a restart — does not repeat it (criterion 8)")
    void announcesOnTransitionOnly() {
        RecordingNotifier notifier = new RecordingNotifier();
        UpdateStatusRepository repo = memoryRepo(null);
        UpdateCheckService svc = new UpdateCheckService(registryWith("1.21.1", "1.21.2"), repo,
                running("1.21.1"), notifier, true, "P1D");

        svc.check();
        svc.check();
        // Simulate a restart: a fresh service instance against the same persisted row.
        new UpdateCheckService(registryWith("1.21.1", "1.21.2"), repo, running("1.21.1"), notifier, true, "P1D").check();

        assertThat(notifier.announced).containsExactly("1.21.2");
    }

    @Test
    @DisplayName("a notifier that delivered nothing leaves the version unannounced for the next check")
    void undeliveredIsNotStamped() {
        RecordingNotifier notifier = new RecordingNotifier();
        notifier.delivered = false;
        UpdateCheckService svc = new UpdateCheckService(registryWith("1.21.1", "1.21.2"), memoryRepo(null),
                running("1.21.1"), notifier, true, "P1D");
        svc.check();
        svc.check();
        assertThat(notifier.announced).as("retried because nothing reached anybody").hasSize(2);
    }

    @Test
    @DisplayName("a newer version that is then withdrawn does not stay 'available'")
    void withdrawnVersion() {
        UpdateStatus previous = new UpdateStatus();
        previous.setNewestVersion("1.21.2");
        UpdateCheckService svc = new UpdateCheckService(registryWith("1.21.1"), memoryRepo(previous),
                running("1.21.1"), new RecordingNotifier(), true, "P1D");
        assertThat(svc.check().updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("a registry error longer than the column is truncated, not thrown from the save")
    void longErrorIsTruncated() {
        RegistryClient registry = mock(RegistryClient.class);
        when(registry.repository()).thenReturn("example/app");
        when(registry.listTags()).thenThrow(new IllegalStateException("x".repeat(2000)));
        UpdateStatusRepository repo = memoryRepo(null);
        UpdateCheckService s = new UpdateCheckService(registry, repo, running("1.21.1"), new RecordingNotifier(), true, "P1D");
        UpdateSnapshot snap = s.check();
        assertThat(snap.lastError()).hasSizeLessThanOrEqualTo(UpdateCheckService.MAX_ERROR_LENGTH);
        assertThat(repo.findById(UpdateStatus.SINGLETON_ID)).get()
                .extracting(UpdateStatus::getLastError).asString().hasSizeLessThanOrEqualTo(UpdateCheckService.MAX_ERROR_LENGTH);
    }

    @Test
    @DisplayName("a failing save never escapes check() — the scheduler must not fail identically for ever")
    void persistFailureDoesNotThrow() {
        UpdateStatusRepository repo = mock(UpdateStatusRepository.class);
        when(repo.findById(UpdateStatus.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any(UpdateStatus.class))).thenThrow(new RuntimeException("disk full"));
        UpdateCheckService s = new UpdateCheckService(registryWith("1.22.0"), repo, running("1.21.1"), new RecordingNotifier(), true, "P1D");
        UpdateSnapshot snap = s.check();
        assertThat(snap.newestVersion()).as("what was measured is still returned").isEqualTo("1.22.0");
    }

    @Test
    @DisplayName("an empty tag list keeps the previous list and records an error — never wipes rollback targets")
    void emptyTagListKeepsPreviousList() {
        UpdateStatus seed = new UpdateStatus();
        seed.setKnownVersions("1.21.1,1.21.0");
        seed.setNewestVersion("1.21.1");
        UpdateStatusRepository repo = memoryRepo(seed);
        UpdateCheckService s = new UpdateCheckService(registryWith(), repo, running("1.21.1"), new RecordingNotifier(), true, "P1D");
        UpdateSnapshot snap = s.check();
        assertThat(snap.knownVersions()).containsExactly("1.21.1", "1.21.0");
        assertThat(snap.newestVersion()).isEqualTo("1.21.1");
        assertThat(snap.lastError()).contains("no release versions");
    }

    @Test
    @DisplayName("a check while one is running returns the last-known result instead of racing the row")
    void concurrentCheckReturnsCurrent() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RegistryClient registry = mock(RegistryClient.class);
        when(registry.repository()).thenReturn("example/app");
        when(registry.listTags()).thenAnswer(inv -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of("1.22.0");
        });
        UpdateStatusRepository repo = memoryRepo(null);
        UpdateCheckService s = new UpdateCheckService(registry, repo, running("1.21.1"), new RecordingNotifier(), true, "P1D");
        Thread first = new Thread(s::check);
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        UpdateSnapshot second = s.check();
        assertThat(System.nanoTime() - started).as("did not wait for the first check").isLessThan(TimeUnit.SECONDS.toNanos(2));
        assertThat(second.newestVersion()).as("the last-known result, which is nothing yet").isNull();

        release.countDown();
        first.join(5000);
        verify(registry, org.mockito.Mockito.times(1)).listTags();
    }
}
