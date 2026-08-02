package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The last-admin guard (#58).
 *
 * <p>Local sign-in is the break-glass route: roles come from Omnissa Access
 * group membership, so if the tenant is unreachable or the role map is wrong, a
 * local admin is the only way back in. Removing the last enabled one would leave
 * nobody able to administer the tool exactly when it matters most, recoverable
 * only by editing the database by hand.
 */
class LastAdminGuardTest {

    private LocalAccountService service;
    private UserAccountRepository repository;

    /**
     * Builds a mutable authorities list, matching what a real Hibernate-loaded
     * {@code UserAccount} carries — its {@code @ManyToMany} collection is
     * always one of Hibernate's own mutable wrappers, never a locked list.
     * {@link LocalAccountService#setRoles} edits that collection in place
     * (see its javadoc for why), so a fixture built from an immutable list
     * would fail in a way a real entity never does.
     */
    private UserAccount account(String username, boolean enabled, AuthorityName... roles) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEnabled(enabled);
        List<Authority> authorities = new ArrayList<>();
        for (AuthorityName role : roles) {
            Authority authority = new Authority();
            authority.setAuthorityName(role);
            authorities.add(authority);
        }
        user.setAuthorities(authorities);
        return user;
    }

    @BeforeEach
    void setUp() {
        service = new LocalAccountService();
        repository = mock(UserAccountRepository.class);
        ReflectionTestUtils.setField(service, "userAccountRepository", repository);
    }

    @Test
    void theOnlyAdminCannotBeDisabledDeletedOrDemoted() {
        UserAccount admin = account("admin", true, AuthorityName.ROLE_ADMIN);
        when(repository.findAll()).thenReturn(List.of(admin, account("dean", true, AuthorityName.ROLE_VIEWER)));

        assertThrows(LocalAccountService.LastAdminException.class,
                () -> service.guardLastAdmin(admin, false, "Disabling"));
        assertThrows(LocalAccountService.LastAdminException.class,
                () -> service.guardLastAdmin(admin, false, "Deleting"));
    }

    @Test
    void theMessageExplainsWhyRatherThanJustRefusing() {
        UserAccount admin = account("admin", true, AuthorityName.ROLE_ADMIN);
        when(repository.findAll()).thenReturn(List.of(admin));

        LocalAccountService.LastAdminException e = assertThrows(
                LocalAccountService.LastAdminException.class,
                () -> service.guardLastAdmin(admin, false, "Deleting"));

        assertTrue(e.getMessage().contains("admin"), e.getMessage());
        assertTrue(e.getMessage().contains("only enabled local administrator"), e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("omnissa access"),
                "the message must say WHY this matters: " + e.getMessage());
    }

    @Test
    void aSecondAdminReleasesTheGuard() {
        UserAccount first = account("admin", true, AuthorityName.ROLE_ADMIN);
        when(repository.findAll()).thenReturn(
                List.of(first, account("dean", true, AuthorityName.ROLE_ADMIN)));

        assertDoesNotThrow(() -> service.guardLastAdmin(first, false, "Deleting"));
    }

    /** A disabled admin is not a way back in, so it does not satisfy the guard. */
    @Test
    void aDisabledAdminDoesNotCount() {
        UserAccount active = account("admin", true, AuthorityName.ROLE_ADMIN);
        when(repository.findAll()).thenReturn(
                List.of(active, account("old-admin", false, AuthorityName.ROLE_ADMIN)));

        assertEquals(1, service.enabledAdminCount());
        assertThrows(LocalAccountService.LastAdminException.class,
                () -> service.guardLastAdmin(active, false, "Disabling"));
    }

    @Test
    void changesThatKeepTheAccountAdminAreAllowed() {
        UserAccount admin = account("admin", true, AuthorityName.ROLE_ADMIN);
        when(repository.findAll()).thenReturn(List.of(admin));

        // Still an admin afterwards — e.g. adding APPROVER alongside ADMIN.
        assertDoesNotThrow(() -> service.guardLastAdmin(admin, true, "Changing roles on"));
    }

    /** Nothing to protect when the account was not an enabled admin to begin with. */
    @Test
    void nonAdminAccountsAreUnaffected() {
        UserAccount viewer = account("dean", true, AuthorityName.ROLE_VIEWER);
        when(repository.findAll()).thenReturn(List.of(
                account("admin", true, AuthorityName.ROLE_ADMIN), viewer));

        assertDoesNotThrow(() -> service.guardLastAdmin(viewer, false, "Deleting"));
    }

    @Test
    void setRolesReplacesRatherThanAccumulates() {
        UserAccount user = account("dean", true, AuthorityName.ROLE_VIEWER);
        service.setRoles(user, Set.of(AuthorityName.ROLE_APPROVER, AuthorityName.ROLE_AUDITOR));

        List<String> names = user.getAuthorityEntities().stream()
                .map(a -> a.getAuthorityName().name()).sorted().toList();
        assertEquals(List.of("ROLE_APPROVER", "ROLE_AUDITOR"), names);
    }

    @Test
    void nullAuthoritiesAreNotAdmin() {
        UserAccount user = new UserAccount();
        user.setUsername("orphan");
        user.setEnabled(true);
        user.setAuthorities(null);

        assertEquals(false, service.isAdmin(user));
    }
}
