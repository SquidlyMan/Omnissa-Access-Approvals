package com.omnissa.access.approval.update;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the check on its own pool. The interval is an ISO-8601 duration
 * ({@code P1D}, {@code PT6H}) read through {@link UpdateInterval}, which
 * substitutes the default for a value it cannot parse rather than letting the
 * setting fail startup.
 */
@Component
public class UpdateScheduler {

    private final UpdateCheckService service;

    public UpdateScheduler(UpdateCheckService service) {
        this.service = service;
    }

    @Scheduled(scheduler = UpdateSchedulerConfig.UPDATE_CHECK_SCHEDULER,
               fixedDelayString = "#{@updateInterval.millis()}",
               initialDelayString = "PT2M")
    public void poll() {
        if (service.isEnabled()) {
            service.check();
        }
    }
}
