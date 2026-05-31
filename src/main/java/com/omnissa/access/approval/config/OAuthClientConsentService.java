package com.omnissa.access.approval.config;

import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * On startup, optionally disables the Omnissa Access user consent prompt on the
 * configured OIDC admin login client. Requires omnissa.admin-oauth.disable-consent=true
 * and a configured ApprovalService (bootstrap) client with admin rights.
 *
 * When disable-consent is not set, logs a one-time tip so the admin knows the option exists.
 */
@Component
@Order(3)
public class OAuthClientConsentService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(OAuthClientConsentService.class);

    @Autowired
    private OmnissaServerRepository serverRepository;

    @Value("${omnissa.admin-oauth.client-id:}")
    private String adminClientId;

    @Value("${omnissa.admin-oauth.disable-consent:false}")
    private boolean disableConsent;

    @Override
    public void run(ApplicationArguments args) {
        if (adminClientId.isBlank()) return;

        if (!disableConsent) {
            logger.info("Setup tip: The '{}' OIDC client may have 'User Consent Prompt' enabled in " +
                        "Omnissa Access. Admins will see a consent screen on their first OAuth2 login. " +
                        "Set omnissa.admin-oauth.disable-consent=true to have the app disable it automatically on startup.",
                        adminClientId);
            return;
        }

        if (serverRepository.count() == 0) {
            logger.warn("Cannot disable consent prompt: Omnissa Access server config not present yet. " +
                        "Will retry on next startup once the server is configured.");
            return;
        }

        OmnissaServer server = serverRepository.findAll().get(0);
        OmnissaRestClient restClient = new OmnissaRestClient(server);
        String url = "https://" + server.getUrl() + Paths.OAUTH2_CLIENTS + "/" + adminClientId;

        try {
            ResponseEntity<Map> getResponse = restClient.exchange(url, HttpMethod.GET, null, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> clientConfig = new HashMap<>(getResponse.getBody());

            if (!clientConfig.containsKey("displayUserConsent")) {
                logger.warn("Could not find 'displayUserConsent' field on OIDC client '{}'. " +
                            "Disable it manually in the Omnissa Access console.", adminClientId);
                return;
            }

            if (!Boolean.TRUE.equals(clientConfig.get("displayUserConsent"))) {
                logger.info("User consent prompt is already disabled on OIDC client '{}'.", adminClientId);
                return;
            }

            clientConfig.put("displayUserConsent", false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restClient.exchange(url, HttpMethod.PUT, new HttpEntity<>(clientConfig, headers), Map.class);
            logger.info("Successfully disabled user consent prompt on OIDC client '{}'.", adminClientId);

        } catch (Exception e) {
            logger.error("Failed to disable consent prompt on OIDC client '{}': {}. " +
                         "Disable it manually in the Omnissa Access console under the client settings.",
                         adminClientId, e.getMessage());
        }
    }
}
