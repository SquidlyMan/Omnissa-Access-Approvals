package com.omnissa.access.approval.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Write models for local account management (#58).
 *
 * <p>Separate records rather than one permissive shape, so a caller cannot
 * reach a field the endpoint did not intend to expose — the same reasoning that
 * removed the {@code authorities} array from user creation.
 */
public final class AccountRequests {

    private AccountRequests() {
    }

    /**
     * A local user changing their own password.
     *
     * <p>The current password is required even though the session already
     * proves identity: it makes an unattended browser insufficient to take over
     * the account, and it is the one credential an attacker with a stolen
     * session does not have.
     */
    public record ChangePassword(
            @NotNull String currentPassword,
            @NotNull String newPassword) {
    }

    /** An admin resetting someone else's password — no current password to know. */
    public record ResetPassword(@NotNull String newPassword) {
    }

    public record SetEnabled(@NotNull Boolean enabled) {
    }

    /** Full replacement rather than add/remove, so the result is never ambiguous. */
    public record SetRoles(@NotEmpty List<String> roles) {
    }

}
