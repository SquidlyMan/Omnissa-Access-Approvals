package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.AutoRule;
import com.omnissa.access.approval.repository.AutoRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * CRUD API for auto-approval rules. Authenticated by default —
 * intentionally NOT in the security permitAll list.
 */
@RestController
@RequestMapping("/api/rules")
public class RulesController {

    @Autowired
    AutoRuleRepository autoRuleRepository;

    @GetMapping
    public ResponseEntity<List<AutoRule>> listRules() {
        return ResponseEntity.ok(autoRuleRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<?> createRule(@RequestBody AutoRule rule) {
        String error = validate(rule);
        if (error != null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", error));
        }
        rule.setId(null);
        return ResponseEntity.ok(autoRuleRepository.save(rule));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody AutoRule rule) {
        if (!autoRuleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        String error = validate(rule);
        if (error != null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", error));
        }
        rule.setId(id);
        return ResponseEntity.ok(autoRuleRepository.save(rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable Long id) {
        if (!autoRuleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        autoRuleRepository.deleteById(id);
        return ResponseEntity.ok(null);
    }

    private String validate(AutoRule rule) {
        if (!"approve".equals(rule.getAction()) && !"reject".equals(rule.getAction())) {
            return "action must be \"approve\" or \"reject\"";
        }
        boolean hasMatchCriteria = notBlank(rule.getAppPattern()) || notBlank(rule.getGroupName());
        if (rule.getExpiryDays() != null) {
            if (rule.getExpiryDays() <= 0) {
                return "expiryDays must be greater than 0";
            }
            if (!"reject".equals(rule.getAction())) {
                return "expiryDays rules must have action \"reject\"";
            }
        } else if (!hasMatchCriteria) {
            return "rule must set expiryDays or at least one of appPattern/groupName";
        }
        if (rule.getGrantTtlMinutes() != null) {
            if (rule.getGrantTtlMinutes() <= 0) {
                return "grantTtlMinutes must be greater than 0";
            }
            if (!"approve".equals(rule.getAction())) {
                return "grantTtlMinutes (JIT) applies only to \"approve\" rules";
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
