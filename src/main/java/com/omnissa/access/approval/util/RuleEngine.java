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
            if (matches(rule, request)) {
                return rule;
            }
        }
        return null;
    }

    private boolean matches(AutoRule rule, CalloutRequest request) {
        boolean hasPattern = notBlank(rule.getAppPattern());
        boolean hasGroup = notBlank(rule.getGroupName());
        // A rule with neither criterion never matches.
        if (!hasPattern && !hasGroup) {
            return false;
        }
        if (hasPattern && !matchesAppPattern(rule.getAppPattern(), request.getResourceName())) {
            return false;
        }
        if (hasGroup && !matchesGroup(rule.getGroupName(), request.getUserAttributes())) {
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
