package com.omnissa.access.approval.config;

import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the OmnissaServer config from properties/env vars on first startup
 * if the database is empty. This lets the app start fully configured without
 * requiring a manual POST to /api/config/server.
 *
 * Set via application-local.properties or environment variables:
 *   OMNISSA_BOOTSTRAP_URL, OMNISSA_BOOTSTRAP_CLIENT_ID, OMNISSA_BOOTSTRAP_CLIENT_SECRET
 */
@Component
public class BootstrapService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapService.class);

    @Autowired
    private OmnissaServerRepository repository;

    @Value("${omnissa.bootstrap.url:}")
    private String bootstrapUrl;

    @Value("${omnissa.bootstrap.client-id:}")
    private String bootstrapClientId;

    @Value("${omnissa.bootstrap.client-secret:}")
    private String bootstrapClientSecret;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            logger.info("Omnissa Access config already present — skipping bootstrap");
            return;
        }

        if (bootstrapUrl.isBlank() || bootstrapClientId.isBlank() || bootstrapClientSecret.isBlank()) {
            logger.info("No bootstrap credentials set. Use the setup wizard at /setup to configure.");
            return;
        }

        OmnissaServer server = new OmnissaServer();
        server.setUrl(bootstrapUrl);
        server.setClientId(bootstrapClientId);
        server.setClientSecret(bootstrapClientSecret);
        repository.save(server);

        logger.info("Bootstrapped Omnissa Access config for tenant: {}", bootstrapUrl);
    }
}
