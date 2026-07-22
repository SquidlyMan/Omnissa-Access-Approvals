package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.security.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceCurrentAdminTest {

    private final AuditService auditService = new AuditService();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Object principal) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(principal);
        when(auth.getName()).thenReturn("token-name");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void noAuthenticationResolvesToSystem() {
        SecurityContextHolder.clearContext();
        assertThat(auditService.currentAdmin()).isEqualTo("system");
    }

    @Test
    void anonymousResolvesToSystem() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(anon);
        assertThat(auditService.currentAdmin()).isEqualTo("system");
    }

    @Test
    void unauthenticatedTokenResolvesToSystem() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertThat(auditService.currentAdmin()).isEqualTo("system");
    }

    @Test
    void oidcPrefersPreferredUsername() {
        OidcUser user = mock(OidcUser.class);
        when(user.getPreferredUsername()).thenReturn("jdoe@corp");
        authenticateWith(user);
        assertThat(auditService.currentAdmin()).isEqualTo("jdoe@corp");
    }

    @Test
    void oidcFallsBackToEmailWhenPreferredUsernameBlank() {
        OidcUser user = mock(OidcUser.class);
        when(user.getPreferredUsername()).thenReturn("  ");
        when(user.getEmail()).thenReturn("jane@corp.com");
        authenticateWith(user);
        assertThat(auditService.currentAdmin()).isEqualTo("jane@corp.com");
    }

    @Test
    void oidcFallsBackToNameWhenPreferredUsernameAndEmailBlank() {
        OidcUser user = mock(OidcUser.class);
        when(user.getPreferredUsername()).thenReturn(null);
        when(user.getEmail()).thenReturn("");
        authenticateWith(user);
        assertThat(auditService.currentAdmin()).isEqualTo("token-name");
    }

    @Test
    void localUserAccountResolvesToUsername() {
        UserAccount account = new UserAccount();
        account.setUsername("localadmin");
        authenticateWith(account);
        assertThat(auditService.currentAdmin()).isEqualTo("localadmin");
    }

    @Test
    void unknownPrincipalTypeFallsBackToName() {
        authenticateWith("some-plain-principal");
        assertThat(auditService.currentAdmin()).isEqualTo("token-name");
    }
}
