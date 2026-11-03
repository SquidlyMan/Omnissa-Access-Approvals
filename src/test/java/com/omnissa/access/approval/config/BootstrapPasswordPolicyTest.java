package com.omnissa.access.approval.config;

import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import com.omnissa.access.approval.service.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The break-glass admin password meets the same policy as every other one.
 * Until 1.23.5 it was the one credential the policy never saw: a first run
 * with {@code OMNISSA_BOOTSTRAP_ADMIN_PASSWORD=admin} went through while the
 * UI refused the same value from a user.
 */
class BootstrapPasswordPolicyTest {

    private UserBootstrapService service;
    private UserAccountRepository repository;

    @BeforeEach
    void wire() {
        repository = mock(UserAccountRepository.class);
        when(repository.count()).thenReturn(0L);

        PasswordPolicy policy = new PasswordPolicy();
        ReflectionTestUtils.setField(policy, "configuredMinLength", 12);
        ReflectionTestUtils.setField(policy, "minDistinct", 5);
        ReflectionTestUtils.setField(policy, "blockUsername", true);
        ReflectionTestUtils.setField(policy, "blocklistFile", "");
        ReflectionTestUtils.invokeMethod(policy, "load");

        service = new UserBootstrapService();
        ReflectionTestUtils.setField(service, "userAccountRepository", repository);
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(service, "passwordPolicy", policy);
        ReflectionTestUtils.setField(service, "adminUsername", "admin");
        ReflectionTestUtils.setField(service, "adminEmail", "");
    }

    @Test
    @DisplayName("a weak bootstrap password stops the start with the variable named")
    void weakPasswordIsRefused() {
        ReflectionTestUtils.setField(service, "adminPassword", "admin");

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OMNISSA_BOOTSTRAP_ADMIN_PASSWORD")
                .hasMessageContaining("admin");
        verify(repository, never()).save(any(UserAccount.class));
    }

    @Test
    @DisplayName("a password that meets the policy creates the account")
    void strongPasswordCreatesTheAccount() {
        ReflectionTestUtils.setField(service, "adminPassword", "correct horse battery staple");

        service.run(null);

        verify(repository).save(any(UserAccount.class));
    }
}
