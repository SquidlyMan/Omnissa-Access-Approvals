package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.dto.AccountRequests;
import com.omnissa.access.approval.dto.CreateUserRequest;
import com.omnissa.access.approval.dto.UserSummary;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import com.omnissa.access.approval.service.LocalAccountService;
import com.omnissa.access.approval.service.PasswordPolicy;
import com.omnissa.access.approval.util.AuditService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping(value = Mappings.USERS)
public class UserAccountController {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditService auditService;

    @Autowired
    private LocalAccountService localAccounts;

    /**
     * Creates a local account. The request model carries no {@code authorities}
     * and no {@code enabled} flag — both are set here — so a caller cannot
     * grant itself a role. New accounts get the least-privileged role,
     * {@code ROLE_VIEWER}; elevating one is a separate, deliberate act.
     */
    @PostMapping
    public ResponseEntity<?> newUser(@RequestBody @Valid CreateUserRequest request) {
        if (userAccountRepository.findByUsername(request.username()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A user with that username already exists"));
        }

        String weakness = PasswordPolicy.validate(request.password(), request.username());
        if (weakness != null) {
            return ResponseEntity.badRequest().body(Map.of("error", weakness));
        }

        Authority role = new Authority();
        role.setAuthorityName(AuthorityName.ROLE_VIEWER);

        UserAccount user = new UserAccount();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName() == null ? "" : request.firstName());
        user.setLastName(request.lastName() == null ? "" : request.lastName());
        user.setEmail(request.email());
        user.setEnabled(true);
        user.setAuthorities(List.of(role));

        userAccountRepository.save(user);
        auditService.record("user-created", null, null,
                "Local account '" + request.username() + "' created with ROLE_VIEWER");

        return ResponseEntity.ok(UserSummary.from(user));
    }

    @GetMapping
    public ResponseEntity<List<UserSummary>> getAllUsers() {
        return ResponseEntity.ok(userAccountRepository.findAll().stream()
                .map(UserSummary::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserSummary> getUser(@PathVariable Long id) {
        return userAccountRepository.findById(id)
                .map(user -> ResponseEntity.ok(UserSummary.from(user)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Account management (#58) ────────────────────────────────────────────
    // The bootstrap admin is a permanent credential — UserBootstrapService only
    // runs when the user table is empty, so changing the env var does nothing on
    // an existing install. Without these endpoints it could never be rotated,
    // disabled or removed except by editing the database by hand.

    /**
     * Change your own password. Available to any signed-in local account, not
     * just admins — a user should not need someone else to rotate their own
     * credential.
     */
    @PutMapping("/me/password")
    public ResponseEntity<?> changeOwnPassword(
            @AuthenticationPrincipal UserAccount principal,
            @RequestBody @Valid AccountRequests.ChangePassword request) {

        if (principal == null) {
            // An OIDC session has no local password to change; its credential
            // lives in Omnissa Access.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "You are signed in with Omnissa Access. "
                            + "Change that password in your identity provider."));
        }

        UserAccount user = userAccountRepository.findByUsername(principal.getUsername());
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            auditService.record("password-change-failed", null, null,
                    "Incorrect current password supplied for '" + user.getUsername() + "'");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Current password is incorrect."));
        }

        String weakness = PasswordPolicy.validate(request.newPassword(), user.getUsername());
        if (weakness != null) {
            return ResponseEntity.badRequest().body(Map.of("error", weakness));
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userAccountRepository.save(user);
        auditService.record("password-changed", null, null,
                "Local account '" + user.getUsername() + "' changed its own password");
        return ResponseEntity.ok(Map.of("changed", true));
    }

    /** Admin reset of another account's password — no current password to know. */
    @PutMapping("/{id}/password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id,
                                           @RequestBody @Valid AccountRequests.ResetPassword request) {
        UserAccount user = userAccountRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        String weakness = PasswordPolicy.validate(request.newPassword(), user.getUsername());
        if (weakness != null) {
            return ResponseEntity.badRequest().body(Map.of("error", weakness));
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userAccountRepository.save(user);
        auditService.record("password-reset", null, null,
                "Password for local account '" + user.getUsername() + "' reset by "
                        + auditService.currentAdmin());
        return ResponseEntity.ok(UserSummary.from(user));
    }

    /** Enable or disable an account. Disabling the last admin is refused. */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id,
                                        @RequestBody @Valid AccountRequests.SetEnabled request) {
        UserAccount user = userAccountRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            localAccounts.guardLastAdmin(user, request.enabled(), "Disabling");
        } catch (LocalAccountService.LastAdminException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }

        user.setEnabled(request.enabled());
        userAccountRepository.save(user);
        auditService.record(request.enabled() ? "account-enabled" : "account-disabled", null, null,
                "Local account '" + user.getUsername() + "' "
                        + (request.enabled() ? "enabled" : "disabled") + " by "
                        + auditService.currentAdmin());
        return ResponseEntity.ok(UserSummary.from(user));
    }

    /** Replace an account's roles. Demoting the last admin is refused. */
    @PutMapping("/{id}/roles")
    public ResponseEntity<?> setRoles(@PathVariable Long id,
                                      @RequestBody @Valid AccountRequests.SetRoles request) {
        UserAccount user = userAccountRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        Set<AuthorityName> roles = new LinkedHashSet<>();
        for (String name : request.roles()) {
            String normalized = name == null ? "" : name.trim().toUpperCase();
            if (!normalized.startsWith("ROLE_")) {
                normalized = "ROLE_" + normalized;
            }
            try {
                roles.add(AuthorityName.valueOf(normalized));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unknown role '" + name + "'. Known roles: ADMIN, APPROVER, VIEWER, AUDITOR"));
            }
        }

        try {
            localAccounts.guardLastAdmin(user, roles.contains(AuthorityName.ROLE_ADMIN), "Removing the Admin role from");
        } catch (LocalAccountService.LastAdminException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }

        localAccounts.setRoles(user, roles);
        userAccountRepository.save(user);
        auditService.record("account-roles-changed", null, null,
                "Roles for local account '" + user.getUsername() + "' set to " + roles
                        + " by " + auditService.currentAdmin());
        return ResponseEntity.ok(UserSummary.from(user));
    }

    /** Delete an account. Deleting the last admin is refused. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        UserAccount user = userAccountRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            localAccounts.guardLastAdmin(user, false, "Deleting");
        } catch (LocalAccountService.LastAdminException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }

        String username = user.getUsername();
        // Audit before deleting, so the record survives even if the delete races.
        auditService.record("account-deleted", null, null,
                "Local account '" + username + "' permanently deleted by " + auditService.currentAdmin());
        userAccountRepository.delete(user);
        return ResponseEntity.ok(Map.of("deleted", true, "username", username));
    }
}
