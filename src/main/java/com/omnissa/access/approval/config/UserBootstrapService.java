package com.omnissa.access.approval.config;

import com.omnissa.access.approval.model.security.Authority;
import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.model.security.UserAccount;
import com.omnissa.access.approval.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the first local admin account from properties/env vars on first startup
 * if the user table is empty. This unblocks the chicken-and-egg problem of
 * needing a login to create users when no users exist yet.
 *
 * Set via application-local.properties or environment variables:
 *   OMNISSA_BOOTSTRAP_ADMIN_USERNAME, OMNISSA_BOOTSTRAP_ADMIN_PASSWORD, OMNISSA_BOOTSTRAP_ADMIN_EMAIL
 */
@Component
@Order(2)
public class UserBootstrapService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserBootstrapService.class);

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${omnissa.bootstrap.admin-username:}")
    private String adminUsername;

    @Value("${omnissa.bootstrap.admin-password:}")
    private String adminPassword;

    @Value("${omnissa.bootstrap.admin-email:}")
    private String adminEmail;

    @Override
    public void run(ApplicationArguments args) {
        if (userAccountRepository.count() > 0) {
            logger.info("User accounts already present — skipping admin bootstrap");
            return;
        }

        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            logger.warn("No admin credentials set (omnissa.bootstrap.admin-username/password). " +
                        "Configure an Omnissa OIDC client for OAuth2 login, or set these " +
                        "properties to auto-create the first local admin account.");
            return;
        }

        Authority adminRole = new Authority();
        adminRole.setAuthorityName(AuthorityName.ROLE_ADMIN);

        UserAccount admin = new UserAccount();
        admin.setUsername(adminUsername);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setFirstName("Admin");
        admin.setLastName("");
        admin.setEmail(adminEmail.isBlank() ? adminUsername + "@localhost" : adminEmail);
        admin.setEnabled(true);
        admin.setAuthorities(List.of(adminRole));

        userAccountRepository.save(admin);
        logger.info("Created initial admin account: {}", adminUsername);
    }
}
