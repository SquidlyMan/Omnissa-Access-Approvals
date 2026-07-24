package com.omnissa.access.approval.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Slack user → app approver mapping (#50). A valid signature proves the
 * message came from the workspace, not that the clicker may approve — so only
 * explicitly-mapped users resolve; everyone else is rejected.
 */
class SlackApproverMapTest {

    private static final String MAP = "U0123ABC:dean@flaming.ws, U0456DEF:jane.doe";

    @Test
    void mapsListedUsers() {
        assertEquals("dean@flaming.ws", SlackController.mapApprover(MAP, "U0123ABC"));
        assertEquals("jane.doe", SlackController.mapApprover(MAP, "U0456DEF"));
    }

    @Test
    void unmappedUserIsRejected() {
        assertNull(SlackController.mapApprover(MAP, "U9999ZZZ"));
    }

    @Test
    void blankMapOrIdRejects() {
        assertNull(SlackController.mapApprover("", "U0123ABC"));
        assertNull(SlackController.mapApprover(MAP, ""));
        assertNull(SlackController.mapApprover(null, "U0123ABC"));
    }
}
