package com.omnissa.access.approval.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only notifier until real channels exist: says so in the log and reports
 * nothing delivered, so the version stays unannounced for whichever channel is
 * wired up later.
 */
@Component
public class LoggingUpdateNotifier implements UpdateNotifier {

    private static final Logger logger = LoggerFactory.getLogger(LoggingUpdateNotifier.class);

    @Override
    public boolean updateAvailable(String runningVersion, String newestVersion) {
        logger.info("Update available: {} (running {}). No notifier is configured; the admin console shows it.",
                newestVersion, runningVersion);
        return false;
    }
}
