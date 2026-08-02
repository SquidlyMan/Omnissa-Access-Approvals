package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.model.CalloutRequest;
import com.omnissa.access.approval.repository.AutoRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Evaluates MATCH auto-rules (appPattern and/or groupName, expiryDays null)
 * against an incoming activation request. EXPIRY rules are handled by
 * {@link com.omnissa.access.approval.config.RuleScheduler}.
 */
@Service
public class RuleEngine {

    @Autowired
    private AutoRuleRepository autoRuleRepository;

    /**
     * Returns the first enabled MATCH rule that matches the request, or null.
     *
     * Precedence contract: enabled MATCH rules are evaluated in ascending
     * rule ID order (oldest rule first) and the first match wins.
     */
    public AutoRule evaluate(CalloutRequest request) {
        for (AutoRule rule : autoRuleRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))) {
            if (!rule.isEnabled() || rule.getExpiryDays() != null) {
                continue;
            }
            if (matchesMatchRule(rule, request)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Does this MATCH rule select the request?
     *
     * <p>A rule with neither criterion selects <strong>nothing</strong>. On the
     * arrival path that is the safe reading: an empty rule is an unfinished one,
     * and treating it as "everything" would auto-decide every incoming request
     * the moment somebody saved a half-filled form.
     */
    public boolean matchesMatchRule(AutoRule rule, CalloutRequest request) {
        return matches(rule, request, false);
    }

    /**
     * Does this EXPIRY rule select the request?
     *
     * <p>A rule with neither criterion selects <strong>everything</strong> — the
     * opposite of {@link #matchesMatchRule}, and deliberately so.
     *
     * <p>The two paths mean different things by an empty rule. "Auto-reject
     * anything pending more than 3 days" is the ordinary expiry rule, and
     * {@code RulesController.validate} explicitly permits it: match criteria are
     * only required when {@code expiryDays} is null. Applying the arrival-path
     * reading here would make that rule select nothing — it would sit enabled
     * and green while never rejecting anything again, and requesters would wait
     * indefinitely on approvals Access holds open until it receives a decision.
     *
     * <p>Criteria, when present, are honoured. Until this method existed the
     * expiry sweep ignored them entirely, so a single enabled rule rejected
     * every pending request past its age regardless of application or group.
     */
    public boolean matchesExpiryRule(AutoRule rule, CalloutRequest request) {
        return matches(rule, request, true);
    }

    /**
     * Same appPattern/groupName matching, for callers with criteria that
     * don't live on an {@link AutoRule} — currently {@code ApprovalChain}
     * (#53). {@code emptySelectsEverything=false} (unfinished-config-selects-
     * nothing) is the right default for anything evaluated on arrival, same
     * reasoning as {@link #matchesMatchRule}.
     */
    public boolean matchesCriteria(String appPattern, String groupName, CalloutRequest request,
                                   boolean emptySelectsEverything) {
        return matches(appPattern, groupName, request, emptySelectsEverything);
    }

    private boolean matches(AutoRule rule, CalloutRequest request, boolean emptySelectsEverything) {
        return matches(rule.getAppPattern(), rule.getGroupName(), request, emptySelectsEverything);
    }

    private boolean matches(String appPattern, String groupName, CalloutRequest request,
                            boolean emptySelectsEverything) {
        boolean hasPattern = notBlank(appPattern);
        boolean hasGroup = notBlank(groupName);
        if (!hasPattern && !hasGroup) {
            return emptySelectsEverything;
        }
        if (hasPattern && !matchesAppPattern(appPattern, request.getResourceName())) {
            return false;
        }
        if (hasGroup && !matchesGroup(groupName, request.getUserAttributes())) {
            return false;
        }
        return true;
    }

    /**
     * Case-insensitive match of the resource name against an exact name or
     * a '*'-wildcard pattern ('*' → regex .*, everything else quoted).
     */
    private boolean matchesAppPattern(String pattern, String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String regex = Arrays.stream(pattern.split("\\*", -1))
                .map(part -> part.isEmpty() ? "" : Pattern.quote(part))
                .collect(Collectors.joining(".*"));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(resourceName).matches();
    }

    private boolean matchesGroup(String groupName, Map<String, List<String>> userAttributes) {
        if (userAttributes == null) {
            return false;
        }
        List<String> groups = userAttributes.get("groupNames");
        if (groups == null) {
            return false;
        }
        return groups.stream().anyMatch(g -> g != null && g.equalsIgnoreCase(groupName));
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
