package com.omnissa.access.approval.model;

/**
 * Result of attempting to push a Hub Notification (#51/#53 additional
 * delivery channel). Mirrors {@link RevokeOutcome}/{@code DecisionOutcome}'s
 * pattern of reporting what actually happened rather than assuming success.
 */
public enum HubNotificationOutcome {
    /** Access accepted the notification (2xx). */
    SENT,
    /** No Omnissa Access tenant is configured yet. */
    NOT_CONFIGURED,
    /** The tenant returned 404 for the Hub Notifications path — not enabled/licensed here. */
    UNAVAILABLE,
    /** Access reachable but rejected or failed the request for some other reason. */
    FAILED
}
