package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.security.GroupRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who holds a given application role, resolved from Omnissa Access — the
 * shared answer to "who should be told about this?" for escalation (#51) and
 * chain stages (#53).
 *
 * <p>Works by reverse-lookup of {@code OMNISSA_ROLE_MAP}: the groups an
 * operator already mapped to a role <em>are</em>, by definition, the people
 * holding it. That is deliberate — it means there is no second list of
 * approvers to maintain and drift out of step with Access, which is the
 * failure that removed an earlier chat-approval feature from this project
 * (a separately-maintained approver map kept working for people who had
 * already lost the role, i.e. it failed <em>open</em>).
 *
 * <p>Never cached. Every call re-reads Access through {@link
 * AccessGroupService}, so removing someone from the group stops them being a
 * recipient on the very next call, with nothing stale in between. If Access
 * is unreachable the result is an empty list rather than a remembered one —
 * a missed notification is recoverable; notifying someone who no longer holds
 * the role is not the kind of mistake this should be able to make.
 */
@Service
public class ApproverDirectoryService {

    private static final Logger logger = LoggerFactory.getLogger(ApproverDirectoryService.class);

    @Autowired
    private AccessGroupService accessGroupService;

    /** Same config {@code GroupRoleMapper}/{@code SecurityConfig} already read. */
    @Value("${omnissa.rbac.role-map:}")
    private String roleMap;

    /**
     * Everyone in any Access group mapped to {@code role}, de-duplicated by
     * SCIM id (a person in two mapped groups appears once). Never throws;
     * returns an empty list when nothing resolves.
     */
    public List<GroupMember> membersHolding(AuthorityName role) {
        Map<String, GroupMember> byScimId = new LinkedHashMap<>();
        try {
            Map<String, AuthorityName> parsed = GroupRoleMapper.parse(roleMap);
            parsed.forEach((groupId, mapped) -> {
                if (mapped != role) {
                    return;
                }
                for (GroupMember member : accessGroupService.resolveMembers(groupId)) {
                    if (member.scimId() != null) {
                        byScimId.putIfAbsent(member.scimId(), member);
                    }
                }
            });
        } catch (Exception e) {
            logger.warn("Could not resolve members holding {}: {}", role, e.getMessage());
            return List.of();
        }
        if (byScimId.isEmpty()) {
            logger.warn("No Access group is mapped to {} in omnissa.rbac.role-map, or none of the "
                    + "mapped groups resolved to members — nobody will be notified for it", role);
        }
        return new ArrayList<>(byScimId.values());
    }

    /**
     * Members of one specific Access group id — the {@code approverType=GROUP}
     * case for chain stages.
     */
    public List<GroupMember> membersOfGroup(String groupId) {
        try {
            return accessGroupService.resolveMembers(groupId);
        } catch (Exception e) {
            logger.warn("Could not resolve members of group {}: {}", groupId, e.getMessage());
            return List.of();
        }
    }

    /**
     * The approver pool for escalation: everyone holding {@code ROLE_APPROVER},
     * plus everyone holding {@code ROLE_ADMIN} (an admin can decide anything, so
     * excluding them would mean escalating past the people most able to act).
     */
    public List<GroupMember> escalationRecipients() {
        Map<String, GroupMember> byScimId = new LinkedHashMap<>();
        for (GroupMember m : membersHolding(AuthorityName.ROLE_APPROVER)) {
            if (m.scimId() != null) {
                byScimId.putIfAbsent(m.scimId(), m);
            }
        }
        for (GroupMember m : membersHolding(AuthorityName.ROLE_ADMIN)) {
            if (m.scimId() != null) {
                byScimId.putIfAbsent(m.scimId(), m);
            }
        }
        return new ArrayList<>(byScimId.values());
    }
}
