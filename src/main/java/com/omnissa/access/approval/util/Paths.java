package com.omnissa.access.approval.util;

public class Paths {

    public static final String ROOT_PATH      = "/SAAS/jersey/manager/api";
    public static final String APPROVALS      = "/SAAS/API/1.0/REST/admin/approvals";
    public static final String OAUTH2_CLIENTS = ROOT_PATH + "/oauth2clients";

    public static final String CATALOG_SEARCH  = ROOT_PATH + "/catalogitems/search";
    public static final String LICENSE         = ROOT_PATH + "/catalogitems/{catalog-id}/license";
    public static final String APPROVAL_POLICY = ROOT_PATH + "/entitlements/definitions/catalogitems/{catalog-id}/approval";

    // Entitlements (JIT, #49). GET the catalog item's entitled subjects; PUT a
    // per-user exclusion (negative entitlement) to revoke; DELETE it to restore.
    public static final String ENTITLEMENTS_CATALOGITEM = ROOT_PATH + "/entitlements/definitions/catalogitems/{catalog-id}";
    public static final String ENTITLEMENTS_USER        = ENTITLEMENTS_CATALOGITEM + "/users/{scim-id}";

    // SCIM user directory — fallback resolution of a requester to their SCIM id.
    public static final String SCIM_USERS = ROOT_PATH + "/scim/Users";
    public static final String SCIM_USER  = SCIM_USERS + "/{scim-id}";

    // SCIM group directory — group-member resolution (#51/#53 recipient lookup).
    public static final String SCIM_GROUP = ROOT_PATH + "/scim/Groups/{group-id}";

    // Hub Notifications Service — an additional (notify-only) delivery channel
    // for #51/#53. Root is NOT under ROOT_PATH: it's a sibling product surface
    // on the same tenant host, confirmed reachable with the same service-client
    // token. Schema verified against Omnissa's own "Workspace ONE Notifications
    // Service Guide" (developer.omnissa.com), not guessed.
    public static final String HUB_NOTIFICATIONS_ROOT       = "/ws1notifications/api/v1";
    public static final String HUB_NOTIFICATIONS_USER       = HUB_NOTIFICATIONS_ROOT + "/users/{user-id}/notifications";
    public static final String HUB_NOTIFICATIONS_DISTRIBUTED = HUB_NOTIFICATIONS_ROOT + "/distributed_notifications";
}
