package com.omnissa.access.approval.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local login throttling (#62 follow-on).
 *
 * <p>The local form was the only credential-accepting endpoint with no rate
 * limiting, so the break-glass admin password could be guessed at full LAN
 * speed. The design choice under test is <em>delay, not lockout</em>: locking an
 * account would let an attacker deny access to the one credential that exists
 * for emergencies.
 */
class LoginThrottleTest {

    private static final String IP = "10.88.88.50";
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle();
    }

    @Test
    void ordinaryTyposCostNothing() {
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            throttle.recordFailure(IP, "dean");
        }
        assertEquals(0, throttle.delayMillis(IP, "dean"),
                "a few mistakes must not punish a legitimate user");
        assertFalse(throttle.shouldReject(IP));
    }

    @Test
    void delayGrowsWithRepeatedFailures() {
        long previous = 0;
        for (int i = 0; i < 8; i++) {
            throttle.recordFailure(IP, "dean");
            long delay = throttle.delayMillis(IP, "dean");
            assertTrue(delay >= previous, "delay should not decrease: " + delay + " after " + previous);
            previous = delay;
        }
        assertTrue(previous > 0, "sustained guessing must be slowed");
    }

    @Test
    void delayIsCappedSoARequestThreadIsNeverHeldLong() {
        for (int i = 0; i < 100; i++) {
            throttle.recordFailure(IP, "dean");
        }
        assertTrue(throttle.delayMillis(IP, "dean") <= LoginThrottle.MAX_IP_DELAY_MILLIS,
                "an unbounded sleep would be its own denial of service");
    }

    @Test
    void aPersistentAddressIsEventuallyRefusedOutright() {
        for (int i = 0; i < LoginThrottle.IP_REJECT_THRESHOLD; i++) {
            throttle.recordFailure(IP, "dean");
        }
        assertTrue(throttle.shouldReject(IP),
                "sustained attempts from one source should stop occupying threads");
    }

    /**
     * The heart of the design. An attacker spread across many addresses drives up
     * the per-username counter, which the real owner shares — so that counter may
     * only ever delay, never refuse, or the attacker gains a lockout lever
     * against the emergency account.
     */
    @Test
    void aTargetedUsernameIsNeverLockedOutOnlySlowed() {
        for (int attacker = 0; attacker < 40; attacker++) {
            throttle.recordFailure("203.0.113." + attacker, "admin");
        }

        String cleanIp = "10.88.88.99";
        assertFalse(throttle.shouldReject(cleanIp),
                "the real admin from a clean address must still be able to sign in");
        assertTrue(throttle.delayMillis(cleanIp, "admin") <= LoginThrottle.MAX_USERNAME_DELAY_MILLIS,
                "and must not be delayed as harshly as the attacking address");
    }

    @Test
    void successClearsTheSlate() {
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure(IP, "dean");
        }
        assertTrue(throttle.delayMillis(IP, "dean") > 0);

        throttle.recordSuccess(IP, "dean");

        assertEquals(0, throttle.delayMillis(IP, "dean"));
        assertFalse(throttle.shouldReject(IP));
    }

    @Test
    void usernamesAreMatchedRegardlessOfCase() {
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure("203.0.113.7", "Admin");
        }
        assertTrue(throttle.delayMillis("10.88.88.99", "admin") > 0,
                "varying the case must not reset the counter");
    }

    @Test
    void aMissingUsernameDoesNotBreakAccounting() {
        throttle.recordFailure(IP, null);
        throttle.recordFailure(IP, "");
        assertEquals(0, throttle.delayMillis(IP, null));
    }

    @Test
    void separateAddressesAreTrackedSeparately() {
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure("203.0.113.1", "dean");
        }
        assertEquals(0, throttle.delayMillis("10.88.88.77", "someone-else"));
    }
}
