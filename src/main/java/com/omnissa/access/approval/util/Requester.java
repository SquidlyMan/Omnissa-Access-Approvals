package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.CalloutRequest;

import java.util.List;
import java.util.Map;

/**
 * Who an access request is for.
 *
 * <p>Omnissa Access identifies the requester by a numeric {@code userId} and
 * carries the human details in a {@code userAttributes} map that is not always
 * populated. Both are kept: the id is the only field guaranteed present and the
 * only stable key, while the name and email are what make an audit trail
 * legible to a person reading it a year later.
 *
 * <p>Extracted so audit records, chat notifications and the UI describe the same
 * person the same way — the label previously lived privately inside
 * {@code WebhookNotifier}, so nothing else could reuse it.
 */
public record Requester(String id, String name, String email) {

    public static final Requester UNKNOWN = new Requester(null, null, null);

    public static Requester from(CalloutRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        Map<String, List<String>> attrs = request.getUserAttributes();
        String first = firstAttr(attrs, "firstName");
        String last = firstAttr(attrs, "lastName");
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (name.isBlank()) {
            name = firstAttr(attrs, "userName");
        }
        return new Requester(request.getUserId(), blankToNull(name), firstAttr(attrs, "email"));
    }

    /**
     * Display form: {@code "Dean Flaming (dean@flaming.ws) · 751802"}, degrading
     * to whichever parts are known — an audit entry should never read as a bare
     * number when a name was available.
     */
    public String label() {
        StringBuilder text = new StringBuilder();
        if (name != null) {
            text.append(name);
        }
        if (email != null && !email.equalsIgnoreCase(name)) {
            text.append(text.isEmpty() ? "" : " ").append('(').append(email).append(')');
        }
        if (id != null && !id.isBlank()) {
            text.append(text.isEmpty() ? "user " : " · ").append(id);
        }
        return text.isEmpty() ? "unknown user" : text.toString();
    }

    /**
     * Compact form for chat notifications, where the full label would be noise:
     * name, else email, else {@code "user <id>"}. Same data, shorter rendering —
     * the point of sharing this type is that identity is derived once, not that
     * every surface must print it identically.
     */
    public String shortLabel() {
        if (name != null) {
            return name;
        }
        if (email != null) {
            return email;
        }
        return id != null && !id.isBlank() ? "user " + id : "unknown user";
    }

    public boolean isKnown() {
        return id != null || name != null || email != null;
    }

    private static String firstAttr(Map<String, List<String>> attrs, String key) {
        if (attrs == null) {
            return null;
        }
        List<String> values = attrs.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
