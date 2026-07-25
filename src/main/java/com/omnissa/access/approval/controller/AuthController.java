package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.security.UserAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Claims that carry no identity value and only add noise when reading a
     * token's group membership off this endpoint.
     */
    private static final List<String> NOISE_CLAIMS =
            List.of("at_hash", "c_hash", "nonce", "aud", "azp", "auth_time", "iat", "exp", "jti");

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal UserAccount localUser,
            Authentication authentication) {

        if (oidcUser != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("loginType", "oauth2");
            body.put("username", oidcUser.getPreferredUsername() != null
                    ? oidcUser.getPreferredUsername() : oidcUser.getEmail());
            body.put("email", oidcUser.getEmail() != null ? oidcUser.getEmail() : "");
            body.put("name", oidcUser.getFullName() != null ? oidcUser.getFullName() : "");
            body.put("authorities", authoritiesOf(authentication));
            return ResponseEntity.ok(body);
        }

        if (localUser != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("loginType", "local");
            body.put("username", localUser.getUsername());
            body.put("email", localUser.getEmail() != null ? localUser.getEmail() : "");
            body.put("name", localUser.getUsername());
            body.put("authorities", authoritiesOf(authentication));
            return ResponseEntity.ok(body);
        }

        return ResponseEntity.status(401).build();
    }

    /**
     * Diagnostic: what the identity provider actually said about the signed-in
     * user.
     *
     * <p>Role mapping keys off the group claim, and a tenant's
     * {@code scopes_supported} tells you the claim <em>exists</em> but not what
     * its values look like — display names, distinguished names or opaque ids.
     * Sign in and read this endpoint to get the real values before configuring
     * any mapping against them. Guessing the format and shipping enforcement
     * together is how an admin locks themselves out.
     *
     * <p>Requires an authenticated session and only ever describes the caller's
     * own token.
     */
    @GetMapping("/claims")
    public ResponseEntity<Map<String, Object>> claims(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal UserAccount localUser,
            Authentication authentication) {

        Map<String, Object> body = new LinkedHashMap<>();

        if (oidcUser == null) {
            body.put("loginType", localUser != null ? "local" : "unknown");
            body.put("note", "Not an OIDC session — no identity-provider claims to show. "
                    + "Sign in with Omnissa Access to inspect group claims.");
            body.put("authorities", authoritiesOf(authentication));
            return ResponseEntity.ok(body);
        }

        Map<String, Object> claims = new TreeMap<>(oidcUser.getClaims());
        NOISE_CLAIMS.forEach(claims::remove);

        body.put("loginType", "oauth2");
        body.put("subject", oidcUser.getSubject());
        body.put("authorities", authoritiesOf(authentication));
        // Surfaced separately because these are the ones role mapping will use.
        body.put("groupNames", oidcUser.getClaim("group_names"));
        body.put("groupIds", oidcUser.getClaim("group_ids"));
        body.put("claims", claims);
        return ResponseEntity.ok(body);
    }

    private List<String> authoritiesOf(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }
}
