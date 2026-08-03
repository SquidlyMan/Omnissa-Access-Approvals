package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.ApprovalChain;
import com.omnissa.access.approval.model.ApprovalStage;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.model.GroupMember;
import com.omnissa.access.approval.model.HubNotificationOutcome;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.repository.ApprovalChainRepository;
import com.omnissa.access.approval.repository.ApprovalStageRepository;
import com.omnissa.access.approval.security.GroupRoleMapper;
import com.omnissa.access.approval.util.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches incoming requests to a chain (#53) and decides whether the acting
 * user may act on a chained request's <em>current</em> stage specifically —
 * new ground for this codebase, since every other decision path treats any
 * APPROVER as eligible for any request (a deliberate, resolved decision —
 * see {@code iga-foundations.md}'s resolved-decisions log — that this class
 * does not change for non-chained requests).
 */
@Service
public class ApprovalChainService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalChainService.class);

    @Autowired private ApprovalChainRepository chainRepository;
    @Autowired private ApprovalStageRepository stageRepository;
    @Autowired private RuleEngine ruleEngine;
    @Autowired private AccessGroupService accessGroupService;
    @Autowired private HubNotificationService hubNotificationService;

    /** Same config GroupRoleMapper/SecurityConfig already read — reused to resolve a ROLE stage's notify targets. */
    @Value("${omnissa.rbac.role-map:}")
    private String roleMap;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    /**
     * First enabled, non-empty chain (ascending id, oldest first) whose
     * appPattern/groupName matches this request — or null. A chain with no
     * stages configured is skipped (logged), never matched: an empty chain
     * would create a request nobody can ever be eligible to decide.
     */
    public ApprovalChain matchChain(CalloutRequest request) {
        for (ApprovalChain chain : chainRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))) {
            if (!chain.isEnabled()) {
                continue;
            }
            if (!ruleEngine.matchesCriteria(chain.getAppPattern(), chain.getGroupName(), request, false)) {
                continue;
            }
            List<ApprovalStage> stages = stagesFor(chain.getId());
            if (stages.isEmpty()) {
                logger.warn("Approval chain #{} ('{}') matched requestId={} but has no stages configured "
                                + "— skipping rather than routing to an undecidable chain",
                        chain.getId(), chain.getName(), request.getRequestId());
                continue;
            }
            return chain;
        }
        return null;
    }

    public List<ApprovalStage> stagesFor(Long chainId) {
        return stageRepository.findByChainIdOrderByStageOrderAsc(chainId);
    }

    /**
     * Null if {@code authentication} may decide this request's current
     * stage (or the request isn't chained); otherwise a human-readable
     * reason it may not.
     *
     * <p>{@code ROLE_ADMIN} always passes — the same break-glass precedent
     * as everywhere else in this project (an admin must always be able to
     * unstick a stuck workflow). Every other case is evaluated per {@link
     * ApprovalStage#getApproverType()}.
     */
    public String ineligibilityReason(CalloutRequest request, Authentication authentication) {
        if (request.getChainId() == null) {
            return null;
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            return "Not authenticated.";
        }
        if (hasAuthority(authentication, "ROLE_ADMIN")) {
            return null;
        }

        List<ApprovalStage> stages = stagesFor(request.getChainId());
        int current = request.getCurrentStage() != null ? request.getCurrentStage() : 1;
        ApprovalStage stage = stages.stream().filter(s -> s.getStageOrder() == current).findFirst().orElse(null);
        if (stage == null) {
            return "This request's chain has no stage " + current + " configured — an admin can still "
                    + "decide it, but the chain needs to be fixed.";
        }

        if ("ROLE".equalsIgnoreCase(stage.getApproverType())) {
            String role = stage.getApproverValue();
            return hasAuthority(authentication, role) ? null
                    : "Stage " + current + " of this chain requires the " + role + " role.";
        }

        if ("GROUP".equalsIgnoreCase(stage.getApproverType())) {
            String identity = callerIdentityForGroupMatch(authentication);
            if (identity == null) {
                return "Stage " + current + " of this chain requires membership in an Access group, "
                        + "and this session has no Access identity to check group membership against "
                        + "(local accounts are never members of an Access group).";
            }
            List<GroupMember> members = accessGroupService.resolveMembers(stage.getApproverValue());
            boolean member = members.stream().anyMatch(m -> matchesIdentity(m, identity));
            return member ? null
                    : "Stage " + current + " of this chain requires membership in an Access group you're "
                            + "not currently resolved as a member of (or Access could not be reached to check).";
        }

        if ("USER".equalsIgnoreCase(stage.getApproverType())) {
            // Matched against the acting session's own identity — the same
            // string AuditService.currentAdmin() resolves, so whoever the
            // audit trail would name as the decider is exactly who this
            // compares. Local accounts work here too (unlike a GROUP stage),
            // because a username is something they actually have.
            String required = stage.getApproverValue();
            if (matchesNamedUser(authentication, required)) {
                return null;
            }
            return "Stage " + current + " of this chain is assigned to " + required + " specifically.";
        }

        return "Stage " + current + " of this chain has an unrecognized approver type ('"
                + stage.getApproverType() + "') — an admin needs to fix it.";
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> authority.equalsIgnoreCase(a.getAuthority()));
    }

    /**
     * The identity to match against a resolved group's members — email if
     * an OIDC session carries one, else null (never a local account: it has
     * no Access group membership at all, so it always fails a GROUP stage,
     * by design).
     */
    private static String callerIdentityForGroupMatch(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcUser.getEmail() != null ? oidcUser.getEmail() : oidcUser.getPreferredUsername();
        }
        return null;
    }

    /**
     * Does the acting session belong to the named individual?
     *
     * <p>Compares against every identity string that session legitimately
     * answers to — preferred_username, email and subject for an OIDC user,
     * the username for a local account — because an operator naming a person
     * in a stage will write whichever of those they know, and the stage would
     * be silently undecidable if only one form matched.
     */
    static boolean matchesNamedUser(Authentication authentication, String required) {
        if (required == null || required.isBlank() || authentication == null) {
            return false;
        }
        String wanted = required.trim();
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            if (equalsAny(wanted, oidcUser.getPreferredUsername(), oidcUser.getEmail(), oidcUser.getSubject())) {
                return true;
            }
        }
        return equalsAny(wanted, authentication.getName());
    }

    private static boolean equalsAny(String wanted, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && wanted.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIdentity(GroupMember member, String identity) {
        return identity.equalsIgnoreCase(member.email())
                || identity.equalsIgnoreCase(member.userName())
                || identity.equalsIgnoreCase(member.userPrincipalName());
    }

    /**
     * Hub-notifies whoever is eligible for {@code stage} that it's now
     * awaiting a decision — called both when a request first enters a chain
     * (stage 1) and after each stage advance. Looks the chain up by id so
     * callers (the ingest path, the decision path) don't each need to carry
     * an {@link ApprovalChain} instance around just for this. Never throws:
     * a notification failure must not affect the decision it's reporting on.
     */
    public void notifyStageApprovers(CalloutRequest request, Long chainId, ApprovalStage stage) {
        try {
            ApprovalChain chain = chainRepository.findById(chainId).orElse(null);
            if (chain == null) {
                logger.warn("Stage notification skipped for requestId={} — chain #{} no longer exists",
                        request.getRequestId(), chainId);
                return;
            }
            List<String> recipients = resolveStageRecipients(stage);
            if (recipients.isEmpty()) {
                logger.warn("Stage {} of chain #{} ('{}') has no resolvable recipients for requestId={} — "
                                + "nobody will be Hub-notified (approverType={}, approverValue={})",
                        stage.getStageOrder(), chain.getId(), chain.getName(), request.getRequestId(),
                        stage.getApproverType(), stage.getApproverValue());
                return;
            }

            String title = "Approval needed: " + safe(request.getResourceName());
            String description = "Stage " + stage.getStageOrder() + " of chain \"" + chain.getName()
                    + "\" is awaiting your decision for " + safe(request.getResourceName())
                    + " (requested by " + safe(request.getUserId()) + ").";
            String deepLink = (appBaseUrl != null && !appBaseUrl.isBlank())
                    ? (appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl)
                            + "/requests/" + request.getRequestId()
                    : null;

            Map<String, HubNotificationOutcome> outcomes =
                    hubNotificationService.notifyUsers(recipients, title, description, deepLink);
            long sent = outcomes.values().stream().filter(o -> o == HubNotificationOutcome.SENT).count();
            logger.info("Stage {} of chain #{} ('{}') requestId={}: Hub-notified {}/{} recipients",
                    stage.getStageOrder(), chain.getId(), chain.getName(), request.getRequestId(),
                    sent, recipients.size());
        } catch (Exception e) {
            logger.warn("Stage notification failed for requestId={} (chain #{}, stage {}): {}",
                    request.getRequestId(), chainId, stage.getStageOrder(), e.getMessage());
        }
    }

    /**
     * GROUP stage: that group's members, directly. ROLE stage: every Access
     * group id that {@code OMNISSA_ROLE_MAP} maps to that role, with their
     * members merged — reuses the existing role-map config rather than
     * requiring a second place to name who holds a role.
     */
    private List<String> resolveStageRecipients(ApprovalStage stage) {
        if ("GROUP".equalsIgnoreCase(stage.getApproverType())) {
            return accessGroupService.resolveMembers(stage.getApproverValue()).stream()
                    .map(GroupMember::scimId)
                    .toList();
        }
        if ("USER".equalsIgnoreCase(stage.getApproverType())) {
            // One named person. Their SCIM id is found by looking them up
            // among the approver pool rather than by a separate directory
            // call — if they hold no approver role there is nobody to notify,
            // which is itself worth surfacing.
            String wanted = stage.getApproverValue();
            return approverDirectoryMembers().stream()
                    .filter(m -> matchesIdentity(m, wanted))
                    .map(GroupMember::scimId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        if ("ROLE".equalsIgnoreCase(stage.getApproverType())) {
            AuthorityName target;
            try {
                target = AuthorityName.valueOf(stage.getApproverValue().toUpperCase());
            } catch (IllegalArgumentException e) {
                return List.of();
            }
            Map<String, AuthorityName> parsedRoleMap = GroupRoleMapper.parse(roleMap);
            Set<String> scimIds = new LinkedHashSet<>();
            parsedRoleMap.forEach((groupId, role) -> {
                if (role == target) {
                    accessGroupService.resolveMembers(groupId).forEach(m -> scimIds.add(m.scimId()));
                }
            });
            return List.copyOf(scimIds);
        }
        return List.of();
    }

    /** The whole approver pool, used to find a named user's SCIM id. */
    private List<GroupMember> approverDirectoryMembers() {
        Map<String, AuthorityName> parsedRoleMap = GroupRoleMapper.parse(roleMap);
        Set<String> seen = new LinkedHashSet<>();
        List<GroupMember> all = new java.util.ArrayList<>();
        parsedRoleMap.keySet().forEach(groupId ->
                accessGroupService.resolveMembers(groupId).forEach(m -> {
                    if (m.scimId() != null && seen.add(m.scimId())) {
                        all.add(m);
                    }
                }));
        return all;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
