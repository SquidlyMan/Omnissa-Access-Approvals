package com.omnissa.access.approval.update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oldest version the picker will hand over without an explicit override.
 *
 * <p>Rolling back is a security decision as much as a version change. Below
 * certain releases, fixed vulnerabilities on the one internet-facing endpoint
 * come back — and a version picker that lets that happen on a mis-click is a
 * foot-gun. Targets below the floor are refused unless the administrator types
 * the exact version to confirm, and the confirmation names what is being
 * reopened.
 *
 * <p>The floor sits at the release that made ingest authenticated, not at the
 * later one that made Access actually <em>send</em> credentials: between the
 * two the tool refuses bare callouts, which is a broken deployment rather than
 * an open one. {@link #reopenedBy} still lists that break for any target
 * below 1.19.9, so the operator choosing 1.19.5 is told what they get.
 *
 * <p>A constant, not configuration: a floor an operator can lower in an
 * environment variable is not a floor.
 */
public final class RollbackFloor {

    public static final Semver FLOOR = new Semver(1, 19, 5);

    /** What returns below each release, oldest last, so the list reads as a descent. */
    private static final Map<Semver, String> REOPENED = new LinkedHashMap<>();

    static {
        REOPENED.put(new Semver(1, 19, 9),
                "the Access OPTIONS probe is exempt from the challenge, so Access never sends "
                        + "credentials even when they are configured — every callout is refused with 401 "
                        + "until the tool is upgraded again (a break, not a hole)");
        REOPENED.put(new Semver(1, 19, 5),
                "callout ingest is unauthenticated — anyone who finds the URL can place a request "
                        + "that looks real, and approving it grants a real entitlement");
        REOPENED.put(new Semver(1, 16, 1),
                "the Slack approver map returns, and it fails open — removing someone in Access "
                        + "leaves their chat buttons working");
    }

    private RollbackFloor() {
    }

    public static boolean isBelowFloor(Semver target) {
        return target.compareTo(FLOOR) < 0;
    }

    /** Every fixed issue that a downgrade to {@code target} would reopen. Empty at or above the floor. */
    public static List<String> reopenedBy(Semver target) {
        return REOPENED.entrySet().stream()
                .filter(e -> target.compareTo(e.getKey()) < 0)
                .map(e -> "below " + e.getKey() + ": " + e.getValue())
                .toList();
    }
}
