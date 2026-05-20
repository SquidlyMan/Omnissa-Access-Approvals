package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.security.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal UserAccount localUser) {

        if (oidcUser != null) {
            return ResponseEntity.ok(Map.of(
                "loginType", "oauth2",
                "username", oidcUser.getPreferredUsername() != null ? oidcUser.getPreferredUsername() : oidcUser.getEmail(),
                "email",    oidcUser.getEmail() != null ? oidcUser.getEmail() : "",
                "name",     oidcUser.getFullName() != null ? oidcUser.getFullName() : ""
            ));
        }

        if (localUser != null) {
            return ResponseEntity.ok(Map.of(
                "loginType", "local",
                "username", localUser.getUsername(),
                "email",    localUser.getEmail() != null ? localUser.getEmail() : "",
                "name",     localUser.getUsername()
            ));
        }

        return ResponseEntity.status(401).build();
    }
}
