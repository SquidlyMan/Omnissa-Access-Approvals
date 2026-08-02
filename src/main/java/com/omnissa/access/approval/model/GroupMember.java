package com.omnissa.access.approval.model;

/**
 * A single member of an Access group, as resolved live via SCIM. Never
 * persisted — see {@link com.omnissa.access.approval.service.AccessGroupService}
 * for why a group's membership must always be read fresh rather than snapshotted.
 */
public record GroupMember(String scimId, String displayName, String userName, String email,
                          String userPrincipalName) {
}
