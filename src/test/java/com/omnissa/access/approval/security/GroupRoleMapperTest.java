package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.AuthorityName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group-claim role mapping (#52). Real ids from the live tenant, so the test
 * fails if the claim shape ever changes underneath us.
 */
class GroupRoleMapperTest {

    private static final String ADMINS    = "05eb7969-9ecf-4b1e-a601-d5be5b58bd65";
    private static final String APPROVERS = "63173f00-249d-4420-8ea0-722179cdea12";
    private static final String AUDITORS  = "4378e8f5-e6da-409b-abb2-53a8db08e028";
    private static final String ALL_USERS = "beae639e-aee4-4fef-a711-1e983839edcb";

    private static final String MAP =
            ADMINS + ":ADMIN," + APPROVERS + ":APPROVER," + AUDITORS + ":AUDITOR";

    @Test
    void mapsConfiguredGroupsToRoles() {
        Map<String, AuthorityName> parsed = GroupRoleMapper.parse(MAP);

        assertEquals(AuthorityName.ROLE_ADMIN, parsed.get(ADMINS));
        assertEquals(AuthorityName.ROLE_APPROVER, parsed.get(APPROVERS));
        assertEquals(AuthorityName.ROLE_AUDITOR, parsed.get(AUDITORS));
        assertEquals(3, parsed.size());
    }

    @Test
    void acceptsRolesWithOrWithoutThePrefix() {
        assertEquals(AuthorityName.ROLE_ADMIN, GroupRoleMapper.parse(ADMINS + ":ADMIN").get(ADMINS));
        assertEquals(AuthorityName.ROLE_ADMIN, GroupRoleMapper.parse(ADMINS + ":ROLE_ADMIN").get(ADMINS));
        assertEquals(AuthorityName.ROLE_ADMIN, GroupRoleMapper.parse(ADMINS + ": admin ").get(ADMINS));
    }

    @Test
    void malformedEntriesAreSkippedNotFatal() {
        Map<String, AuthorityName> parsed =
                GroupRoleMapper.parse("no-colon," + ADMINS + ":ADMIN,:ORPHAN," + APPROVERS + ":NOT_A_ROLE,");

        assertEquals(Map.of(ADMINS, AuthorityName.ROLE_ADMIN), parsed);
    }

    @Test
    void blankMapGrantsOnlyTheDefaultRole() {
        assertEquals(Set.of(AuthorityName.ROLE_VIEWER),
                GroupRoleMapper.rolesFor(GroupRoleMapper.parse(""), List.of(ADMINS)));
    }

    @Test
    void unmatchedMembershipFallsBackToViewer() {
        Set<AuthorityName> roles =
                GroupRoleMapper.rolesFor(GroupRoleMapper.parse(MAP), List.of(ALL_USERS));

        assertEquals(Set.of(AuthorityName.ROLE_VIEWER), roles);
    }

    @Test
    void adminMembershipGrantsAdminOnTopOfViewer() {
        Set<AuthorityName> roles = GroupRoleMapper.rolesFor(
                GroupRoleMapper.parse(MAP), List.of(ALL_USERS, ADMINS));

        assertTrue(roles.contains(AuthorityName.ROLE_ADMIN));
        assertTrue(roles.contains(AuthorityName.ROLE_VIEWER));
        assertFalse(roles.contains(AuthorityName.ROLE_APPROVER));
    }

    @Test
    void multipleMatchesAreAdditive() {
        Set<AuthorityName> roles = GroupRoleMapper.rolesFor(
                GroupRoleMapper.parse(MAP), List.of(APPROVERS, AUDITORS));

        assertTrue(roles.contains(AuthorityName.ROLE_APPROVER));
        assertTrue(roles.contains(AuthorityName.ROLE_AUDITOR));
        assertFalse(roles.contains(AuthorityName.ROLE_ADMIN));
    }

    @Test
    void nullAndNonListClaimsAreSafe() {
        assertEquals(List.of(), GroupRoleMapper.groupIdsFrom(null));
        assertEquals(List.of(), GroupRoleMapper.groupIdsFrom("not-a-list"));
        assertEquals(List.of(ADMINS), GroupRoleMapper.groupIdsFrom(List.of(ADMINS)));

        assertEquals(Set.of(AuthorityName.ROLE_VIEWER),
                GroupRoleMapper.rolesFor(GroupRoleMapper.parse(MAP), null));
    }
}
