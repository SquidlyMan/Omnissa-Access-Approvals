package com.omnissa.access.approval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.GroupMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the SCIM group/user response parsing behind #51/#53
 * recipient resolution. Fixtures mirror the shape verified against a live
 * tenant on 2026-08-02: {@code GET /scim/Groups/{id}} returns
 * {@code members[].value}/{@code .display}; {@code GET /scim/Users/{id}}
 * returns {@code emails[0].value}, {@code name.givenName}/{@code familyName},
 * and the workspace extension's {@code userPrincipalName} nested under the
 * literal key {@code urn:scim:schemas:extension:workspace:1.0}.
 */
class AccessGroupServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void parsesMemberRefsFromAGroup() throws Exception {
        JsonNode group = json("""
            {"id":"g1","displayName":"Approvers","members":[
              {"value":"u1","display":"Jane Doe"},
              {"value":"u2","display":"John Smith"}
            ]}""");

        List<AccessGroupService.MemberRef> refs = AccessGroupService.parseMembers(group);

        assertThat(refs).containsExactly(
                new AccessGroupService.MemberRef("u1", "Jane Doe"),
                new AccessGroupService.MemberRef("u2", "John Smith"));
    }

    @Test
    void aGroupWithNoMembersKeyParsesAsEmpty() throws Exception {
        JsonNode group = json("{\"id\":\"g1\",\"displayName\":\"Empty Group\"}");

        assertThat(AccessGroupService.parseMembers(group)).isEmpty();
    }

    @Test
    void aNullGroupParsesAsEmptyRatherThanThrowing() {
        assertThat(AccessGroupService.parseMembers(null)).isEmpty();
    }

    @Test
    void aMemberEntryWithNoValueIsSkipped() throws Exception {
        JsonNode group = json("""
            {"members":[
              {"display":"No SCIM id, unusable"},
              {"value":"u2","display":"John Smith"}
            ]}""");

        List<AccessGroupService.MemberRef> refs = AccessGroupService.parseMembers(group);

        assertThat(refs).containsExactly(new AccessGroupService.MemberRef("u2", "John Smith"));
    }

    @Test
    void parsesFullUserRecord() throws Exception {
        JsonNode user = json("""
            {"userName":"jdoe","emails":[{"value":"jane@corp.com","primary":true}],
             "name":{"givenName":"Jane","familyName":"Doe"},
             "urn:scim:schemas:extension:workspace:1.0":{"userPrincipalName":"jane.doe@corp.onmicrosoft.com"}}""");

        GroupMember member = AccessGroupService.parseUser(
                new AccessGroupService.MemberRef("u1", "group-listing-name"), user);

        assertThat(member.scimId()).isEqualTo("u1");
        assertThat(member.displayName()).isEqualTo("Jane Doe");
        assertThat(member.userName()).isEqualTo("jdoe");
        assertThat(member.email()).isEqualTo("jane@corp.com");
        assertThat(member.userPrincipalName()).isEqualTo("jane.doe@corp.onmicrosoft.com");
    }

    @Test
    void missingNameFallsBackToUserName() throws Exception {
        JsonNode user = json("{\"userName\":\"jdoe\",\"emails\":[{\"value\":\"jane@corp.com\"}]}");

        GroupMember member = AccessGroupService.parseUser(
                new AccessGroupService.MemberRef("u1", "group-listing-name"), user);

        assertThat(member.displayName()).isEqualTo("jdoe");
    }

    @Test
    void missingNameAndUserNameFallsBackToTheGroupListingDisplay() throws Exception {
        JsonNode user = json("{\"emails\":[{\"value\":\"jane@corp.com\"}]}");

        GroupMember member = AccessGroupService.parseUser(
                new AccessGroupService.MemberRef("u1", "group-listing-name"), user);

        assertThat(member.displayName()).isEqualTo("group-listing-name");
    }

    @Test
    void aUserWithNoWorkspaceExtensionBlockHasNullUpnRatherThanThrowing() throws Exception {
        JsonNode user = json("{\"userName\":\"jdoe\",\"emails\":[{\"value\":\"jane@corp.com\"}]}");

        GroupMember member = AccessGroupService.parseUser(
                new AccessGroupService.MemberRef("u1", "fallback"), user);

        assertThat(member.userPrincipalName()).isNull();
    }

    @Test
    void aUserWithNoEmailsHasNullEmailRatherThanThrowing() throws Exception {
        JsonNode user = json("{\"userName\":\"jdoe\"}");

        GroupMember member = AccessGroupService.parseUser(
                new AccessGroupService.MemberRef("u1", "fallback"), user);

        assertThat(member.email()).isNull();
    }
}
