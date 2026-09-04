package com.omnissa.access.approval.update;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the check on its own pool. Interval is an ISO-8601 duration
 * ({@code P1D}, {@code PT6H}), matching every other schedule in the tool.
 */
@Component
public class UpdateScheduler {

    private final UpdateCheckService service;

    public UpdateScheduler(UpdateCheckService service) {
        this.service = service;
    }

    @Scheduled(scheduler = UpdateSchedulerConfig.UPDATE_CHECK_SCHEDULER,
               fixedDelayString = "${omnissa.update.check-interval:P1D}",
               initialDelayString = "PT2M")
    public void poll() {
        if (service.isEnabled()) {
            service.check();
        }
    }
}
