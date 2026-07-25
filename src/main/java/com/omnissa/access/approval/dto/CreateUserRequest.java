package com.omnissa.access.approval.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Write model for creating a local user account.
 *
 * <p>Deliberately has no {@code authorities} or {@code enabled} field. Binding
 * the entity directly let any authenticated caller post
 * {@code "authorities":[{"authorityName":"ROLE_ADMIN"}]} and mint themselves an
 * admin account — a privilege-escalation backdoor that survives every other
 * hardening. Roles are assigned by the server, never by the request.
 */
public record CreateUserRequest(
        @NotNull @Size(min = 4, max = 50) String username,
        @NotNull @Size(min = 4, max = 100) String password,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @NotNull @Email @Size(max = 100) String email) {
}
