package com.omnissa.access.approval.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The check interval, made safe. {@code @Scheduled(fixedDelayString = "${...}")}
 * parses the property itself and a value it cannot read fails the whole
 * application context — an approval tool that will not start because someone
 * typed {@code 1d} into an update-check setting is the wrong trade. A value
 * that is too small would also hammer a public registry from every install
 * that made the same typo. So: unreadable → the default, with a warning; below
 * the minimum → the minimum, with a warning.
 */
@Component("updateInterval")
public class UpdateInterval {

    private static final Logger logger = LoggerFactory.getLogger(UpdateInterval.class);

    static final Duration DEFAULT = Duration.ofDays(1);
    static final Duration MINIMUM = Duration.ofMinutes(5);

    private final Duration effective;

    public UpdateInterval(@Value("${omnissa.update.check-interval:P1D}") String configured) {
        this.effective = resolve(configured);
    }

    static Duration resolve(String configured) {
        Duration parsed;
        try {
            parsed = Duration.parse(configured == null ? "" : configured.trim());
        } catch (DateTimeParseException e) {
            logger.warn("OMNISSA_UPDATE_CHECK_INTERVAL '{}' is not an ISO-8601 duration (PT6H, P1D); using {}",
                    configured, DEFAULT);
            return DEFAULT;
        }
        if (parsed.compareTo(MINIMUM) < 0) {
            logger.warn("OMNISSA_UPDATE_CHECK_INTERVAL {} is below the minimum {}; using the minimum", parsed, MINIMUM);
            return MINIMUM;
        }
        return parsed;
    }

    public long millis() {
        return effective.toMillis();
    }

    public Duration value() {
        return effective;
    }
}
