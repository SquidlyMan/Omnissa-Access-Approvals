package com.omnissa.access.approval.util;

import org.springframework.http.HttpHeaders;

public class CustomContentTypes {

    public static final String CATALOG_SEARCH_BULK    = "application/vnd.vmware.horizon.manager.catalog.search.bulk+json";
    public static final String APPROVAL_MESSAGE_REQUEST = "application/vnd.vmware.horizon.manager.approval.message+json";
    // What current Omnissa Access tenants actually send on the approvals callout
    public static final String MESSAGING_MESSAGE      = "application/vnd.vmware.horizon.manager.messaging.message+json";
    public static final String APPROVAL_RESPONSE      = "application/vnd.vmware.horizon.manager.approvals.search.response+json";

    public static HttpHeaders add(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", contentType);
        return headers;
    }
}
