package com.omnissa.access.approval.dto;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.UserAccount;

import java.util.List;

/**
 * Read model for user accounts.
 *
 * <p>Exists so the {@link UserAccount} entity is never serialized directly: it
 * carries the bcrypt password hash, and {@code getPassword()} has no
 * {@code @JsonIgnore}, so returning the entity handed every authenticated
 * caller the local admin's hash for offline cracking.
 */
public record UserSummary(
        Long id,
        String username,
        String firstName,
        String lastName,
        String email,
        boolean enabled,
        List<String> roles) {

    public static UserSummary from(UserAccount user) {
        List<Authority> granted = user.getAuthorityEntities();
        List<String> roles = granted == null ? List.of()
                : granted.stream()
                        .filter(a -> a != null && a.getAuthorityName() != null)
                        .map(a -> a.getAuthorityName().name())
                        .toList();
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.isEnabled(),
                roles);
    }
}
