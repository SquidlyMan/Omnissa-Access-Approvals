package com.omnissa.access.approval.update;

/**
 * Told when a newer version is first observed.
 *
 * <p>The check announces a version once: it stamps the status row with the
 * version it announced only when an implementation reports that it actually
 * reached somebody. A notifier that has nothing configured returns
 * {@code false}, so the first real notifier to be switched on still gets to
 * announce the version that is already waiting.
 */
public interface UpdateNotifier {

    /**
     * @return true when at least one channel delivered — the version is then
     *         recorded as announced and will not be repeated
     */
    boolean updateAvailable(String runningVersion, String newestVersion);
}
