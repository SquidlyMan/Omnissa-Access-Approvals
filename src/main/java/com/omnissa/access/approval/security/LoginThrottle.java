package com.omnissa.access.approval.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows repeated failed local logins.
 *
 * <p>The local login form was the one credential-accepting endpoint with no
 * rate limiting at all, so the break-glass admin password could be guessed at
 * full LAN speed. That matters more now that local sign-in is a permanent
 * fixture rather than a temporary workaround.
 *
 * <p><strong>Progressive delay, not account lockout.</strong> Locking an account
 * after N failures turns a guessing attempt into a denial of service against the
 * one credential that exists for emergencies — an attacker who cannot get in can
 * still ensure nobody else does, exactly when Omnissa Access is unavailable and
 * this is the only way in. Delay defeats brute force without handing anyone that
 * lever.
 *
 * <p>Two counters, treated differently on purpose:
 * <ul>
 *   <li><strong>Per IP</strong> — delay, then hard rejection. A single source
 *       making hundreds of attempts is not a confused user, and rejecting
 *       outright stops it occupying a request thread.</li>
 *   <li><strong>Per username</strong> — delay only, never rejection. This
 *       counter is shared with the genuine owner of the account: an attacker
 *       spread across many IPs could otherwise lock out the real admin by
 *       driving up a counter they also depend on.</li>
 * </ul>
 */
@Component
public class LoginThrottle {

    private static final Logger logger = LoggerFactory.getLogger(LoginThrottle.class);

    /** Attempts before any delay applies — ordinary typos should cost nothing. */
    static final int FREE_ATTEMPTS = 3;

    static final long BASE_DELAY_MILLIS = 250;
    static final long MAX_IP_DELAY_MILLIS = 5_000;

    /** Lower, because this delay can be inflicted on the account's real owner. */
    static final long MAX_USERNAME_DELAY_MILLIS = 2_000;

    /** Sustained attempts from one address stop being served at all. */
    static final int IP_REJECT_THRESHOLD = 25;

    /** Counters expire, so nothing needs clearing by hand. */
    static final long WINDOW_MILLIS = 15 * 60_000L;

    private static final int MAX_TRACKED = 10_000;

    private final ConcurrentHashMap<String, long[]> ipFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> userFailures = new ConcurrentHashMap<>();

    public void recordFailure(String ip, String username) {
        bump(ipFailures, ip);
        if (username != null && !username.isBlank()) {
            bump(userFailures, username.toLowerCase(Locale.ROOT));
        }
        logger.warn("Failed local login for '{}' from {} ({} recent failures from this address)",
                username, ip, count(ipFailures, ip));
    }

    /** A correct password clears the slate for that address and account. */
    public void recordSuccess(String ip, String username) {
        ipFailures.remove(ip);
        if (username != null && !username.isBlank()) {
            userFailures.remove(username.toLowerCase(Locale.ROOT));
        }
    }

    /** True when this address has been trying long enough to stop serving it. */
    public boolean shouldReject(String ip) {
        return count(ipFailures, ip) >= IP_REJECT_THRESHOLD;
    }

    /**
     * How long to hold this attempt before checking the password. Doubles per
     * failure beyond the free allowance, capped so a request thread is never
     * held for long.
     */
    public long delayMillis(String ip, String username) {
        long byIp = delayFor(count(ipFailures, ip), MAX_IP_DELAY_MILLIS);
        long byUser = username == null || username.isBlank()
                ? 0
                : delayFor(count(userFailures, username.toLowerCase(Locale.ROOT)),
                        MAX_USERNAME_DELAY_MILLIS);
        return Math.max(byIp, byUser);
    }

    private static long delayFor(int failures, long cap) {
        if (failures <= FREE_ATTEMPTS) {
            return 0;
        }
        int over = Math.min(failures - FREE_ATTEMPTS, 16);
        long delay = BASE_DELAY_MILLIS << (over - 1);
        return Math.min(delay, cap);
    }

    int count(ConcurrentHashMap<String, long[]> map, String key) {
        long[] entry = map.get(key);
        if (entry == null || System.currentTimeMillis() - entry[0] >= WINDOW_MILLIS) {
            return 0;
        }
        return (int) entry[1];
    }

    private void bump(ConcurrentHashMap<String, long[]> map, String key) {
        long now = System.currentTimeMillis();
        evict(map, now);
        map.compute(key, (k, value) -> {
            if (value == null || now - value[0] >= WINDOW_MILLIS) {
                return new long[]{now, 1};
            }
            value[1]++;
            return value;
        });
    }

    private static void evict(ConcurrentHashMap<String, long[]> map, long now) {
        if (map.size() > MAX_TRACKED) {
            map.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MILLIS);
        }
    }
}
