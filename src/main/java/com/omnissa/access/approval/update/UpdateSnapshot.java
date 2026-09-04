package com.omnissa.access.approval.update;

import java.util.Date;
import java.util.List;

/** What the console is shown. Never carries an exception, only its message. */
public record UpdateSnapshot(
        boolean enabled,
        String checkInterval,
        String runningVersion,
        String newestVersion,
        boolean updateAvailable,
        Date lastCheckedAt,
        String lastError,
        List<String> knownVersions) {
}
