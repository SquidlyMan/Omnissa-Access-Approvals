package com.omnissa.access.approval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves an Access group's members, live, via SCIM — the capability #51
 * (escalation) and #53 (group-approver stages) need to turn "notify the
 * Approvers group" into actual recipients with an email address.
 *
 * <p><strong>Deliberately never cached or persisted.</strong> This project's
 * hard rule is that there must be no second, independently-maintained source
 * of truth for who holds a role or is a member of a group — a prior feature
 * (Slack inline-approval buttons keyed off a separately-maintained approver
 * map) was removed specifically because that second list drifted from the
 * real one and failed <em>open</em>. A snapshot of group membership carries
 * the identical risk. Every call here re-reads Access; a person removed from
 * a group stops being a recipient on the very next call, with nothing to go
 * stale in between. If Access is unreachable, this returns an empty list
 * rather than a remembered one — a missed notification is recoverable, a
 * notification sent to someone no longer in the group is not the kind of
 * mistake this method should be able to make.
 *
 * <p>Verified against a live tenant (2026-08-02): a role-map group id from
 * {@code OMNISSA_ROLE_MAP} (sourced from the OIDC {@code group_ids} claim) is
 * the same id this method's {@code GET /scim/Groups/{id}} call expects — so
 * no new admin configuration is needed to use this against groups already
 * named in the role map. Member emails and the workspace UPN extension were
 * also confirmed present on that tenant's {@code GET /scim/Users/{id}}
 * response; a mobile/phone attribute was not observed on any member checked,
 * so {@link GroupMember} has no field for one — add it if a tenant is
 * confirmed to send it.
 */
@Service
public class AccessGroupService {

    private static final Logger logger = LoggerFactory.getLogger(AccessGroupService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The extension schema Access nests workspace-specific user attributes under. */
    private static final String WORKSPACE_EXTENSION = "urn:scim:schemas:extension:workspace:1.0";

    /**
     * @return this group's members, or an empty list if the group has none,
     *         cannot be found, or Access could not be reached. Never null,
     *         never throws.
     */
    public List<GroupMember> resolveMembers(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }

        OmnissaRestClient restClient = client();
        String base = RestPreconditions.omnissaServerBaseUrl();

        JsonNode group = fetchGroup(restClient, base, groupId);
        List<MemberRef> refs = parseMembers(group);
        if (refs.isEmpty()) {
            return List.of();
        }

        List<GroupMember> members = new ArrayList<>(refs.size());
        for (MemberRef ref : refs) {
            JsonNode user = fetchUser(restClient, base, ref.scimId());
            if (user == null) {
                // One unreadable member must not drop the rest of the group —
                // matches the per-item isolation the JIT sweeps already use.
                logger.warn("Group {}: could not read SCIM user {} ({}), skipping",
                        groupId, ref.scimId(), ref.display());
                continue;
            }
            members.add(parseUser(ref, user));
        }
        return members;
    }

    private OmnissaRestClient client() {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        return new OmnissaRestClient(server);
    }

    private JsonNode fetchGroup(OmnissaRestClient restClient, String base, String groupId) {
        try {
            HttpHeaders accept = new HttpHeaders();
            accept.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = restClient.exchange(base + Paths.SCIM_GROUP, HttpMethod.GET,
                    new HttpEntity<>(accept), String.class, groupId).getBody();
            return MAPPER.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            logger.warn("Could not read SCIM group {}: {}", groupId, e.getMessage());
            return null;
        }
    }

    private JsonNode fetchUser(OmnissaRestClient restClient, String base, String scimId) {
        try {
            HttpHeaders accept = new HttpHeaders();
            accept.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = restClient.exchange(base + Paths.SCIM_USER, HttpMethod.GET,
                    new HttpEntity<>(accept), String.class, scimId).getBody();
            return MAPPER.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            logger.warn("Could not read SCIM user {}: {}", scimId, e.getMessage());
            return null;
        }
    }

    /** Package-private (not {@code private}) so the parsing tests can build one directly. */
    record MemberRef(String scimId, String display) {
    }

    /** A group's {@code members[].value} (SCIM user id) + {@code .display}, pure/static for testing. */
    static List<MemberRef> parseMembers(JsonNode group) {
        List<MemberRef> refs = new ArrayList<>();
        if (group == null) {
            return refs;
        }
        JsonNode members = group.path("members");
        if (!members.isArray()) {
            return refs;
        }
        for (JsonNode member : members) {
            String value = text(member, "value");
            if (value != null) {
                refs.add(new MemberRef(value, text(member, "display")));
            }
        }
        return refs;
    }

    /**
     * Builds a {@link GroupMember} from a {@code GET /scim/Users/{id}} body.
     * Display name prefers given+family name, then {@code userName}, then
     * whatever the group listing itself said about this member — pure/static
     * for testing.
     */
    static GroupMember parseUser(MemberRef ref, JsonNode user) {
        String userName = text(user, "userName");
        String givenName = text(user.path("name"), "givenName");
        String familyName = text(user.path("name"), "familyName");
        String fullName = (givenName != null || familyName != null)
                ? ((givenName != null ? givenName : "") + " " + (familyName != null ? familyName : "")).trim()
                : null;
        String displayName = fullName != null ? fullName : userName != null ? userName : ref.display();

        String email = text(user.path("emails").path(0), "value");
        String upn = text(user.path(WORKSPACE_EXTENSION), "userPrincipalName");

        return new GroupMember(ref.scimId(), displayName, userName, email, upn);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }
}
