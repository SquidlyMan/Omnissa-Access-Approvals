package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.OmnissaServer;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;

/**
 * HTTP client that handles OAuth2 client_credentials token acquisition and injection
 * for calls to Omnissa Access SaaS APIs. No custom SSL configuration is required
 * since SaaS endpoints use certificates from trusted public CAs.
 */
public class OmnissaRestClient {

    private final OmnissaServer server;
    private final RestTemplate restTemplate = new RestTemplate();

    public OmnissaRestClient(OmnissaServer server) {
        this.server = server;
    }

    private String getAccessToken() {
        String credentials = server.getClientId() + ":" + server.getClientSecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        ResponseEntity<Map> response = restTemplate.exchange(
                "https://" + server.getUrl() + "/SAAS/auth/oauthtoken",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        return (String) response.getBody().get("access_token");
    }

    /**
     * Connectivity probe: attempts a client_credentials token fetch and lets
     * any failure propagate to the caller. Used by the /api/config/status check.
     */
    public void checkToken() {
        getAccessToken();
    }

    public <T> ResponseEntity<T> exchange(String url, HttpMethod method,
                                          HttpEntity<?> requestEntity, Class<T> responseType,
                                          Object... uriVars) {
        // HttpEntity.getHeaders() is read-only and new HttpHeaders(map) is backed by
        // (not copied from) the given map — mutating it throws. Copy via putAll.
        HttpHeaders headers = new HttpHeaders();
        if (requestEntity != null) {
            headers.putAll(requestEntity.getHeaders());
        }
        headers.set("Authorization", "Bearer " + getAccessToken());

        HttpEntity<?> newEntity = requestEntity != null
                ? new HttpEntity<>(requestEntity.getBody(), headers)
                : new HttpEntity<>(headers);

        return restTemplate.exchange(url, method, newEntity, responseType, uriVars);
    }

    public <T> T getForObject(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getAccessToken());
        ResponseEntity<T> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), responseType);
        return response.getBody();
    }
}
