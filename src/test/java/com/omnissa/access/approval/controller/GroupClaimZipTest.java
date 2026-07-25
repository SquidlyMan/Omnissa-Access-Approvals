package com.omnissa.access.approval.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Omnissa Access returns group membership as two parallel arrays,
 * {@code group_names} and {@code group_ids}. Role mapping is configured against
 * ids, so the pairing is what makes an opaque map maintainable.
 */
class GroupClaimZipTest {

    @Test
    void pairsNamesWithIdsByIndex() {
        List<Map<String, Object>> groups = AuthController.zipGroups(
                List.of("Dean Only", "IT Admins@flamenet.local"),
                List.of("9f001b51-22f8-42da-b131-ae3fca4de23a", "a41f87a0-282b-4a10-acc8-3c08951bd82f"));

        assertEquals(2, groups.size());
        assertEquals("Dean Only", groups.get(0).get("name"));
        assertEquals("9f001b51-22f8-42da-b131-ae3fca4de23a", groups.get(0).get("id"));
        assertEquals("IT Admins@flamenet.local", groups.get(1).get("name"));
    }

    @Test
    void handlesMissingClaimsWithoutBlowingUp() {
        assertTrue(AuthController.zipGroups(null, null).isEmpty());
    }

    @Test
    void toleratesUnevenArrays() {
        List<Map<String, Object>> groups =
                AuthController.zipGroups(List.of("Only A Name"), List.of());

        assertEquals(1, groups.size());
        assertEquals("Only A Name", groups.get(0).get("name"));
        assertNull(groups.get(0).get("id"));
    }

    @Test
    void ignoresNonListClaims() {
        assertTrue(AuthController.zipGroups("not-a-list", 42).isEmpty());
    }
}
