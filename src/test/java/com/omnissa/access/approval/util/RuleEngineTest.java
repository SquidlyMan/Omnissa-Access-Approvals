package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutOperation;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.AutoRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleEngineTest {

    private AutoRuleRepository repository;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        repository = mock(AutoRuleRepository.class);
        engine = new RuleEngine();
        ReflectionTestUtils.setField(engine, "autoRuleRepository", repository);
    }

    /** The engine iterates the repository in ascending-id order; emulate that ordering here. */
    private void givenRules(AutoRule... rules) {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of(rules));
    }

    private static AutoRule rule(long id, boolean enabled, String action,
                                 String appPattern, String groupName, Integer expiryDays) {
        AutoRule r = new AutoRule();
        r.setId(id);
        r.setEnabled(enabled);
        r.setAction(action);
        r.setAppPattern(appPattern);
        r.setGroupName(groupName);
        r.setExpiryDays(expiryDays);
        return r;
    }

    private static CalloutRequest request(String resourceName, List<String> groups) {
        HashMap<String, List<String>> attrs = null;
        if (groups != null) {
            attrs = new HashMap<>();
            attrs.put("groupNames", new ArrayList<>(groups));
        }
        return new CalloutRequest(CalloutOperation.activation, "req-1", "uuid-1",
                resourceName, "user-1", attrs, null, null, null, null, null);
    }

    @Test
    void exactAppNameMatches() {
        givenRules(rule(1, true, "approve", "Salesforce", null, null));
        assertThat(engine.evaluate(request("Salesforce", null))).isNotNull();
    }

    @Test
    void exactAppNameDoesNotMatchDifferentName() {
        givenRules(rule(1, true, "approve", "Salesforce", null, null));
        assertThat(engine.evaluate(request("Workday", null))).isNull();
    }

    @Test
    void trailingWildcardMatches() {
        givenRules(rule(1, true, "approve", "Sales*", null, null));
        assertThat(engine.evaluate(request("Salesforce", null))).isNotNull();
        assertThat(engine.evaluate(request("Marketing", null))).isNull();
    }

    @Test
    void leadingWildcardMatches() {
        givenRules(rule(1, true, "approve", "*force", null, null));
        assertThat(engine.evaluate(request("Salesforce", null))).isNotNull();
        assertThat(engine.evaluate(request("ForceField", null))).isNull();
    }

    @Test
    void multipleWildcardsMatch() {
        givenRules(rule(1, true, "approve", "*Prod*", null, null));
        assertThat(engine.evaluate(request("MyProdApp", null))).isNotNull();
        assertThat(engine.evaluate(request("Production", null))).isNotNull();
        assertThat(engine.evaluate(request("Staging", null))).isNull();
    }

    @Test
    void barewildcardMatchesEverything() {
        givenRules(rule(1, true, "approve", "*", null, null));
        assertThat(engine.evaluate(request("AnythingAtAll", null))).isNotNull();
    }

    @Test
    void appMatchIsCaseInsensitive() {
        givenRules(rule(1, true, "approve", "salesFORCE", null, null));
        assertThat(engine.evaluate(request("SALESforce", null))).isNotNull();
    }

    @Test
    void wildcardDoesNotSpanUnintendedButRegexCharsAreQuoted() {
        // A dot in the pattern must be literal (Pattern.quote), not "any char".
        givenRules(rule(1, true, "approve", "App.One", null, null));
        assertThat(engine.evaluate(request("App.One", null))).isNotNull();
        assertThat(engine.evaluate(request("AppXOne", null))).isNull();
    }

    @Test
    void groupOnlyMatches() {
        givenRules(rule(1, true, "approve", null, "admins", null));
        assertThat(engine.evaluate(request("Whatever", List.of("users", "admins")))).isNotNull();
        assertThat(engine.evaluate(request("Whatever", List.of("users")))).isNull();
    }

    @Test
    void groupMatchIsCaseInsensitive() {
        givenRules(rule(1, true, "approve", null, "Admins", null));
        assertThat(engine.evaluate(request("Whatever", List.of("ADMINS")))).isNotNull();
    }

    @Test
    void groupOnlyDoesNotMatchWhenNoAttributes() {
        givenRules(rule(1, true, "approve", null, "admins", null));
        assertThat(engine.evaluate(request("Whatever", null))).isNull();
    }

    @Test
    void appAndGroupBothMustMatch() {
        givenRules(rule(1, true, "approve", "Salesforce", "admins", null));
        assertThat(engine.evaluate(request("Salesforce", List.of("admins")))).isNotNull();
        assertThat(engine.evaluate(request("Salesforce", List.of("users")))).isNull();
        assertThat(engine.evaluate(request("Workday", List.of("admins")))).isNull();
    }

    @Test
    void ruleWithNeitherCriterionNeverMatches() {
        givenRules(rule(1, true, "approve", null, null, null));
        assertThat(engine.evaluate(request("Salesforce", List.of("admins")))).isNull();
        assertThat(engine.evaluate(request("", null))).isNull();
    }

    @Test
    void blankCriteriaTreatedAsUnset() {
        givenRules(rule(1, true, "approve", "  ", "  ", null));
        assertThat(engine.evaluate(request("Salesforce", List.of("admins")))).isNull();
    }

    @Test
    void disabledRuleSkipped() {
        givenRules(rule(1, false, "approve", "Salesforce", null, null));
        assertThat(engine.evaluate(request("Salesforce", null))).isNull();
    }

    @Test
    void expiryRuleExcludedFromEvaluate() {
        // expiryDays != null → EXPIRY rule, handled by the scheduler, never by evaluate().
        givenRules(rule(1, true, "reject", "Salesforce", null, 30));
        assertThat(engine.evaluate(request("Salesforce", null))).isNull();
    }

    @Test
    void precedenceLowestEnabledIdWins() {
        AutoRule first = rule(1, true, "approve", "*", null, null);
        AutoRule second = rule(2, true, "reject", "*", null, null);
        givenRules(first, second);
        assertThat(engine.evaluate(request("Anything", null))).isSameAs(first);
    }

    @Test
    void precedenceSkipsDisabledAndPicksNextEnabled() {
        AutoRule disabled = rule(1, false, "approve", "*", null, null);
        AutoRule enabled = rule(2, true, "reject", "*", null, null);
        givenRules(disabled, enabled);
        assertThat(engine.evaluate(request("Anything", null))).isSameAs(enabled);
    }

    @Test
    void noRulesMeansNoMatch() {
        givenRules();
        assertThat(engine.evaluate(request("Salesforce", List.of("admins")))).isNull();
    }
}
