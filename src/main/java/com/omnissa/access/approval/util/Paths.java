package com.omnissa.access.approval.util;

public class Paths {

    public static final String ROOT_PATH      = "/SAAS/jersey/manager/api";
    public static final String APPROVALS      = "/SAAS/API/1.0/REST/admin/approvals";
    public static final String OAUTH2_CLIENTS = ROOT_PATH + "/oauth2clients";

    public static final String CATALOG_SEARCH  = ROOT_PATH + "/catalogitems/search";
    public static final String LICENSE         = ROOT_PATH + "/catalogitems/{catalog-id}/license";
    public static final String APPROVAL_POLICY = ROOT_PATH + "/entitlements/definitions/catalogitems/{catalog-id}/approval";

    // Entitlements (JIT revocation, #49). GET the catalog item's entitled users;
    // DELETE a single user's entitlement to revoke access.
    public static final String ENTITLEMENTS_CATALOGITEM = ROOT_PATH + "/entitlements/definitions/catalogitems/{catalog-id}";
    public static final String ENTITLEMENTS_USER        = ENTITLEMENTS_CATALOGITEM + "/users/{scim-id}";
}
