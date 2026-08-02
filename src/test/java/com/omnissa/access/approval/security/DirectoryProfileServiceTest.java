package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.DirectoryProfile;
import com.omnissa.access.approval.repository.DirectoryProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #51/#53 need a way to resolve a notification recipient from an
 * already-known identity string without a live Access directory lookup this
 * codebase does not have. Capturing what a user's own token told us at login
 * is the fallback — these tests pin down the two things that matter for that
 * to be usable later: it upserts on the identity key {@code AuditService}
 * already uses, and it never lets a persistence failure block sign-in.
 */
class DirectoryProfileServiceTest {

    private final DirectoryProfileRepository repository = mock(DirectoryProfileRepository.class);
    private final DirectoryProfileService service = new DirectoryProfileService();

    DirectoryProfileServiceTest() {
        ReflectionTestUtils.setField(service, "repository", repository);
    }

    private static OidcUser userWith(Map<String, Object> extraClaims) {
        OidcUser user = mock(OidcUser.class);
        when(user.getSubject()).thenReturn("sub-123");
        when(user.getFullName()).thenReturn("Jane Doe");
        when(user.getEmail()).thenReturn("jane@corp.com");
        when(user.getPhoneNumber()).thenReturn(null);
        when(user.getClaims()).thenReturn(extraClaims);
        return user;
    }

    private DirectoryProfile captured() {
        ArgumentCaptor<DirectoryProfile> captor = ArgumentCaptor.forClass(DirectoryProfile.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void newIdentityIsInsertedWithStandardClaims() {
        when(repository.findByIdentity("jane@corp.com")).thenReturn(null);

        service.captureFromLogin(userWith(Map.of()), "jane@corp.com");

        DirectoryProfile saved = captured();
        assertThat(saved.getIdentity()).isEqualTo("jane@corp.com");
        assertThat(saved.getSubject()).isEqualTo("sub-123");
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(saved.getEmail()).isEqualTo("jane@corp.com");
        assertThat(saved.getLastLoginAt()).isNotNull();
    }

    @Test
    void knownIdentityIsUpdatedInPlaceRatherThanDuplicated() {
        DirectoryProfile existing = new DirectoryProfile();
        existing.setId(42L);
        existing.setIdentity("jane@corp.com");
        when(repository.findByIdentity("jane@corp.com")).thenReturn(existing);

        service.captureFromLogin(userWith(Map.of()), "jane@corp.com");

        DirectoryProfile saved = captured();
        assertThat(saved.getId())
                .as("must update the existing row, not insert a second one for the same identity")
                .isEqualTo(42L);
    }

    @Test
    void firstMatchingCandidateClaimIsUsedForUnverifiedAttributes() {
        when(repository.findByIdentity("jane@corp.com")).thenReturn(null);

        service.captureFromLogin(userWith(Map.of(
                "mobilePhone", "555-0100",
                "userPrincipalName", "jane.doe@corp.onmicrosoft.com",
                "sAMAccountName", "jdoe"
        )), "jane@corp.com");

        DirectoryProfile saved = captured();
        assertThat(saved.getMobilePhone()).isEqualTo("555-0100");
        assertThat(saved.getUserPrincipalName()).isEqualTo("jane.doe@corp.onmicrosoft.com");
        assertThat(saved.getSamAccountName()).isEqualTo("jdoe");
    }

    @Test
    void absentCandidateClaimsLeaveFieldsNullRatherThanGuessing() {
        when(repository.findByIdentity("jane@corp.com")).thenReturn(null);

        service.captureFromLogin(userWith(Map.of()), "jane@corp.com");

        DirectoryProfile saved = captured();
        assertThat(saved.getMobilePhone()).isNull();
        assertThat(saved.getUserPrincipalName()).isNull();
        assertThat(saved.getSamAccountName()).isNull();
    }

    @Test
    void blankIdentityIsSkippedWithoutTouchingTheRepository() {
        service.captureFromLogin(userWith(Map.of()), " ");

        verify(repository, never()).save(any());
    }

    @Test
    void aRepositoryFailureIsSwallowedRatherThanPropagated() {
        when(repository.findByIdentity("jane@corp.com")).thenThrow(new RuntimeException("db is down"));

        service.captureFromLogin(userWith(Map.of()), "jane@corp.com");
        // No exception reaching here is the assertion: a directory snapshot
        // must never be able to block or fail a login.
    }

    private static DirectoryProfile any() {
        return org.mockito.ArgumentMatchers.any(DirectoryProfile.class);
    }
}
