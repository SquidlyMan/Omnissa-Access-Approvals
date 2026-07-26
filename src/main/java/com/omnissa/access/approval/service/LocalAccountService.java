package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Local account changes, and the one rule that must never be broken.
 *
 * <p>Local sign-in exists as the <strong>break-glass</strong> route: roles come
 * from Omnissa Access group membership, so if the tenant is unreachable, the
 * OIDC client is misconfigured, or the role map is wrong, a local admin is the
 * only way back in. That makes "the last enabled local admin" a resource worth
 * protecting explicitly — disabling, deleting or demoting it would leave nobody
 * able to administer the tool precisely when administration is most needed, and
 * the only remedy would be editing the database by hand.
 *
 * <p>Note this counts <em>local</em> admins only. An OIDC user holding
 * {@code ROLE_ADMIN} through a group does not satisfy the guard, because the
 * scenarios the break-glass covers are exactly the ones where OIDC does not
 * work.
 */
@Service
public class LocalAccountService {

    @Autowired
    private UserAccountRepository userAccountRepository;

    /** Thrown when a change would remove the last way back in. */
    public static class LastAdminException extends RuntimeException {
        public LastAdminException(String message) {
            super(message);
        }
    }

    public boolean isAdmin(UserAccount user) {
        List<Authority> authorities = user.getAuthorityEntities();
        return authorities != null && authorities.stream()
                .anyMatch(a -> a != null && a.getAuthorityName() == AuthorityName.ROLE_ADMIN);
    }

    /** Enabled local accounts holding ROLE_ADMIN. */
    public long enabledAdminCount() {
        return userAccountRepository.findAll().stream()
                .filter(UserAccount::isEnabled)
                .filter(this::isAdmin)
                .count();
    }

    /**
     * Refuses a change that would leave no enabled local admin.
     *
     * @param stillAdminAfterwards whether the account remains an enabled admin
     *                             once the change is applied
     */
    public void guardLastAdmin(UserAccount user, boolean stillAdminAfterwards, String action) {
        boolean wasAdmin = user.isEnabled() && isAdmin(user);
        if (wasAdmin && !stillAdminAfterwards && enabledAdminCount() <= 1) {
            throw new LastAdminException(
                    "'" + user.getUsername() + "' is the only enabled local administrator. "
                            + action + " it would leave no way to sign in locally if Omnissa Access "
                            + "or the role mapping fails. Grant another local account the Admin role "
                            + "first.");
        }
    }

    /** Replaces an account's roles, rejecting unknown names. */
    public void setRoles(UserAccount user, Set<AuthorityName> roles) {
        List<Authority> authorities = roles.stream().map(role -> {
            Authority authority = new Authority();
            authority.setAuthorityName(role);
            return authority;
        }).toList();
        user.setAuthorities(authorities);
    }
}
