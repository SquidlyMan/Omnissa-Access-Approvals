package com.omnissa.access.approval.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Slack signature check (#50), plus the rejection paths (tampered
 * signature, tampered body, replay, no secret). GOOD_SIG is the HMAC-SHA256 of
 * {@code v0:{ts}:{body}} with SECRET, cross-checked against an independent
 * Python reference implementation — so this pins our HMAC to a known-good value.
 */
class SlackSignatureVerifierTest {

    private static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a5";
    private static final String TS = "1531420618";
    private static final String BODY =
            "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3B&team_domain=testteamnow&"
            + "channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&"
            + "command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands"
            + "%2FT1DC2JH3B%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&"
            + "trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
    private static final String GOOD_SIG =
            "v0=1e5d8cdf7e3743d2f5e2f7a03a212e88b33ed3dea9b451130c06153bf23f79da";

    @Test
    void acceptsValidSlackSignature() {
        assertTrue(SlackSignatureVerifier.isValid(SECRET, TS, GOOD_SIG, BODY, Long.parseLong(TS)));
    }

    @Test
    void rejectsTamperedSignature() {
        assertFalse(SlackSignatureVerifier.isValid(SECRET, TS,
                "v0=deadbeef00000000000000000000000000000000000000000000000000000000", BODY, Long.parseLong(TS)));
    }

    @Test
    void rejectsTamperedBody() {
        assertFalse(SlackSignatureVerifier.isValid(SECRET, TS, GOOD_SIG, BODY + "&evil=1", Long.parseLong(TS)));
    }

    @Test
    void rejectsStaleTimestamp_replayGuard() {
        long now = Long.parseLong(TS) + SlackSignatureVerifier.MAX_SKEW_SECONDS + 60;
        assertFalse(SlackSignatureVerifier.isValid(SECRET, TS, GOOD_SIG, BODY, now));
    }

    @Test
    void rejectsWhenSecretOrHeadersMissing() {
        assertFalse(SlackSignatureVerifier.isValid("", TS, GOOD_SIG, BODY, Long.parseLong(TS)));
        assertFalse(SlackSignatureVerifier.isValid(SECRET, null, GOOD_SIG, BODY, Long.parseLong(TS)));
        assertFalse(SlackSignatureVerifier.isValid(SECRET, TS, null, BODY, Long.parseLong(TS)));
    }
}
