package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.AuthorityName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns Omnissa Access group membership into application roles.
 *
 * <p>Configured as {@code <groupId>:<ROLE>} pairs, matched against the
 * {@code group_ids} claim. Ids rather than names: renaming a group in Access
 * would otherwise silently drop everyone to the default role with no error.
 *
 * <p>Read the ids off {@code GET /api/auth/claims}, which pairs them with their
 * display names.
 *
 * <p><strong>Users are commonly in many groups.</strong> When several map to
 * roles, every matched role is granted — a user in both the approver and
 * auditor groups gets both. That is additive by design; no role subtracts a
 * capability another grants.
 */
public final class GroupRoleMapper {

    private static final Logger logger = LoggerFactory.getLogger(GroupRoleMapper.class);

    /** What a signed-in user gets when no configured group matches. */
    public static final AuthorityName DEFAULT_ROLE = AuthorityName.ROLE_VIEWER;

    private GroupRoleMapper() {
    }

    /**
     * Parses the configured map. Malformed entries are logged and skipped
     * rather than failing startup — a typo should not take the tool offline,
     * and the unmatched group simply grants nothing.
     */
    public static Map<String, AuthorityName> parse(String roleMap) {
        Map<String, AuthorityName> parsed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (roleMap == null || roleMap.isBlank()) {
            return parsed;
        }

        for (String entry : roleMap.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.lastIndexOf(':');
            if (split <= 0 || split == trimmed.length() - 1) {
                logger.warn("Ignoring malformed role-map entry '{}' — expected <groupId>:<ROLE>", trimmed);
                continue;
            }

            String groupId = trimmed.substring(0, split).trim();
            String roleName = trimmed.substring(split + 1).trim().toUpperCase();
            if (!roleName.startsWith("ROLE_")) {
                roleName = "ROLE_" + roleName;
            }

            try {
                parsed.put(groupId, AuthorityName.valueOf(roleName));
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring role-map entry '{}' — '{}' is not a known role. Known roles: {}",
                        trimmed, roleName, List.of(AuthorityName.values()));
            }
        }
        return parsed;
    }

    /**
     * Resolves the roles for a set of group ids.
     *
     * <p>{@link #DEFAULT_ROLE} is a <em>fallback</em>, not a floor: a user whose
     * groups match nothing gets Viewer, but once any group matches, the matched
     * roles are exactly what they get.
     *
     * <p>That distinction is what makes {@code ROLE_AUDITOR} mean anything.
     * Granting Viewer unconditionally would make every role additive on top of
     * it — and since Viewer already includes reading the audit trail, an auditor
     * would hold Viewer's access to the live queue plus nothing extra, which is
     * the opposite of the intent. A role that grants <em>less</em> cannot be
     * expressed in a model where every user starts with Viewer.
     *
     * <p>Matching remains additive among matched groups: someone in both the
     * approver and auditor groups gets both roles.
     */
    public static Set<AuthorityName> rolesFor(Map<String, AuthorityName> roleMap, List<String> groupIds) {
        Set<AuthorityName> matched = new LinkedHashSet<>();

        if (roleMap != null && !roleMap.isEmpty() && groupIds != null) {
            for (String groupId : groupIds) {
                if (groupId == null) {
                    continue;
                }
                AuthorityName role = roleMap.get(groupId.trim());
                if (role != null) {
                    matched.add(role);
                }
            }
        }

        return matched.isEmpty() ? Set.of(DEFAULT_ROLE) : matched;
    }

    /**
     * Describes the case where {@code ROLE_AUDITOR} is held alongside another
     * role, or {@code null} when there is nothing to report.
     *
     * <p>Auditor is the one <em>restrictive</em> role: it withholds the live
     * queue so a compliance reviewer sees the record without visibility of, or
     * influence over, requests in flight. Because role resolution is additive,
     * pairing it with any other role silently defeats that — the union of
     * permissions applies and Auditor contributes nothing. Adding an approver
     * to the auditors group therefore looks like a restriction and is not one.
     *
     * <p>Combined with {@code ROLE_ADMIN} or {@code ROLE_APPROVER} it is also a
     * separation-of-duties conflict: the same person both decides requests and
     * audits those decisions.
     *
     * <p>This is reported rather than corrected. Silently dropping a role would
     * mean a group membership <em>removing</em> access, which is surprising in
     * its own way and could strand someone unexpectedly; the failure worth
     * fixing is that the conflict was invisible.
     */
    public static String auditorConflict(Set<AuthorityName> roles) {
        if (roles == null || !roles.contains(AuthorityName.ROLE_AUDITOR) || roles.size() < 2) {
            return null;
        }

        Set<AuthorityName> others = new LinkedHashSet<>(roles);
        others.remove(AuthorityName.ROLE_AUDITOR);

        boolean canAct = others.contains(AuthorityName.ROLE_ADMIN)
                || others.contains(AuthorityName.ROLE_APPROVER);

        return "ROLE_AUDITOR is held alongside " + others + ", so it has no restrictive effect — "
                + "roles are additive and the union applies, giving full access to the live queue"
                + (canAct ? ", and the same identity both decides requests and audits those decisions "
                            + "(separation-of-duties conflict)" : "")
                + ". Map this user to the auditors group ONLY if the intent is to restrict them.";
    }

    /**
     * Extracts group ids from an OIDC claim value.
     *
     * <p>Access serves {@code group_ids} as an <em>overflow claim</em> — the
     * token carries {@code ovc}/{@code ovl} pointing at the userinfo endpoint
     * rather than the ids themselves once the list is large. Spring's
     * {@code OidcUserService} fetches userinfo and merges it, which is why the
     * claim is readable here; nothing may switch to parsing the ID token alone.
     */
    @SuppressWarnings("unchecked")
    public static List<String> groupIdsFrom(Object claim) {
        if (!(claim instanceof List<?> raw)) {
            return List.of();
        }
        return (List<String>) raw.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
