package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.dto.CreateUserRequest;
import com.omnissa.access.approval.dto.UserSummary;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import com.omnissa.access.approval.util.AuditService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(value = Mappings.USERS)
public class UserAccountController {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditService auditService;

    /**
     * Creates a local account. The request model carries no {@code authorities}
     * and no {@code enabled} flag — both are set here — so a caller cannot
     * grant itself a role. New accounts get {@code ROLE_USER}; elevating one is
     * a separate, deliberate act.
     */
    @PostMapping
    public ResponseEntity<?> newUser(@RequestBody @Valid CreateUserRequest request) {
        if (userAccountRepository.findByUsername(request.username()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A user with that username already exists"));
        }

        Authority role = new Authority();
        role.setAuthorityName(AuthorityName.ROLE_USER);

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
                "Local account '" + request.username() + "' created with ROLE_USER");

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
}
