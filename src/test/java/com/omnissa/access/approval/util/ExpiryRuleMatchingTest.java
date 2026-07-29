package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expiry-rule scoping (#69).
 *
 * <p>The expiry sweep used to consult neither {@code appPattern} nor
 * {@code groupName}, so one enabled rule auto-rejected every pending request
 * past its age regardless of application — an unadvertised decision affecting
 * real entitlements, on an hourly schedule.
 *
 * <p>The obvious fix — reuse the arrival-path matcher — would have been far
 * worse than the bug. That matcher returns <em>false</em> for a rule with no
 * criteria, and a rule with no criteria is the ordinary expiry rule, the one
 * {@code RulesController} explicitly permits. Every such rule would have
 * silently stopped rejecting anything while sitting enabled and green. The
 * first test below is the one that matters; the rest describe the narrowing
 * that was actually wanted.
 */
class ExpiryRuleMatchingTest {

    private final RuleEngine engine = new RuleEngine();

    @Test
    void anExpiryRuleWithNoCriteriaStillSelectsEverything() {
        AutoRule rule = expiryRule(null, null);

        assertTrue(engine.matchesExpiryRule(rule, request("Anything At All", "Anyone")),
                "The ordinary expiry rule carries no criteria. If it stops selecting "
                        + "requests, auto-rejection silently stops for the whole install and "
                        + "requesters wait forever on approvals Access is holding open.");
    }

    @Test
    void theArrivalPathStillTreatsAnEmptyRuleAsMatchingNothing() {
        AutoRule rule = new AutoRule();
        rule.setEnabled(true);
        rule.setAction("approve");

        assertFalse(engine.matchesMatchRule(rule, request("Anything At All", "Anyone")),
                "An unfinished match rule must not auto-decide every incoming request. "
                        + "The two paths mean opposite things by an empty rule, deliberately.");
    }

    @Test
    void anAppPatternNarrowsTheRule() {
        AutoRule rule = expiryRule("Finance*", null);

        assertTrue(engine.matchesExpiryRule(rule, request("Finance Portal", "Anyone")));
        assertFalse(engine.matchesExpiryRule(rule, request("Engineering Wiki", "Anyone")),
                "This is the bug being fixed: before #69 a scoped rule rejected "
                        + "unrelated applications too.");
    }

    @Test
    void aGroupNarrowsTheRule() {
        AutoRule rule = expiryRule(null, "Contractors");

        assertTrue(engine.matchesExpiryRule(rule, request("Any App", "Contractors")));
        assertFalse(engine.matchesExpiryRule(rule, request("Any App", "Employees")));
    }

    @Test
    void bothCriteriaMustHoldWhenBothAreSet() {
        AutoRule rule = expiryRule("Finance*", "Contractors");

        assertTrue(engine.matchesExpiryRule(rule, request("Finance Portal", "Contractors")));
        assertFalse(engine.matchesExpiryRule(rule, request("Finance Portal", "Employees")));
        assertFalse(engine.matchesExpiryRule(rule, request("Engineering Wiki", "Contractors")));
    }

    @Test
    void matchingIsCaseInsensitiveOnBothCriteria() {
        AutoRule rule = expiryRule("finance*", "contractors");

        assertTrue(engine.matchesExpiryRule(rule, request("FINANCE Portal", "CONTRACTORS")),
                "Access group and application names are not case-normalised anywhere, "
                        + "so a rule that only matched exact case would appear not to work.");
    }

    @Test
    void blankCriteriaAreTreatedAsAbsentRatherThanAsAPatternToMatch() {
        AutoRule rule = expiryRule("   ", "");

        assertTrue(engine.matchesExpiryRule(rule, request("Any App", "Anyone")),
                "A form that submits empty strings rather than nulls must not silently "
                        + "produce a rule that matches nothing.");
    }

    @Test
    void aRequestWithNoGroupsDoesNotMatchAGroupScopedRule() {
        AutoRule rule = expiryRule(null, "Contractors");
        CalloutRequest request = new CalloutRequest(CalloutOperation.activation, "req-1",
                "uuid-1", "Any App", "751802", null, null, null, null, null, null);

        assertFalse(engine.matchesExpiryRule(rule, request),
                "Absent attributes must not be read as a wildcard.");
    }

    private static AutoRule expiryRule(String appPattern, String groupName) {
        AutoRule rule = new AutoRule();
        rule.setEnabled(true);
        rule.setAction("reject");
        rule.setExpiryDays(3);
        rule.setAppPattern(appPattern);
        rule.setGroupName(groupName);
        return rule;
    }

    private static CalloutRequest request(String resourceName, String group) {
        HashMap<String, List<String>> attributes = new HashMap<>();
        attributes.put("groupNames", List.of(group));
        return new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                resourceName, "751802", attributes, null, null, null, null, null);
    }
}
