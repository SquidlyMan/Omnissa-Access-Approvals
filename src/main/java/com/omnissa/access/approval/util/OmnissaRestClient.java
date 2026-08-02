package com.omnissa.access.approval.util;

import com.omnissa.access.approval.model.OmnissaServer;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client that handles OAuth2 client_credentials token acquisition and injection
 * for calls to Omnissa Access SaaS APIs. No custom SSL configuration is required
 * since SaaS endpoints use certificates from trusted public CAs.
 *
 * <p><strong>The token is cached process-wide, not per instance.</strong> Every
 * call site constructs a fresh {@code OmnissaRestClient} per call (there is no
 * shared/injected instance), so an instance-level cache would never be hit twice.
 * The cache below is keyed by tenant and lives independently of any one instance.
 */
public class OmnissaRestClient {

    /**
     * Bounded so a hung tenant cannot pin a request thread indefinitely.
     *
     * <p>Without this, an unresponsive Access endpoint blocks whichever thread
     * called it until the OS gives up — survivable while the connectivity check
     * ran once per dashboard load, but not once a monitor polls it every
     * minute, where hung probes accumulate until the pool starves and the
     * monitoring causes the outage it exists to detect.
     *
     * <p>Matches the 5s/5s already used for webhook delivery.
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    /**
     * Used when the token response omits {@code expires_in} (not required by
     * the OAuth2 spec). Conservative on purpose: guessing too long risks
     * calls failing on an expired token, guessing too short just means an
     * extra fetch — the cheaper of the two mistakes.
     */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * Refetch this long before the token's real expiry, so a request that
     * starts an instant before expiry doesn't race a still-in-flight call
     * against an already-dead token.
     */
    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(30);

    /**
     * Keyed by tenant (URL + client id, never the secret), shared by every
     * {@code OmnissaRestClient} instance for that tenant. A plain map guarded
     * by a lock on itself, not {@code ConcurrentHashMap} — call volume here is
     * low enough that a lab tool doesn't need lock-free cleverness, and this
     * avoids two threads racing to fetch the same expired token at once.
     */
    private static final Map<String, CachedToken> TOKEN_CACHE = new HashMap<>();

    private record CachedToken(String value, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private final OmnissaServer server;
    private final RestTemplate restTemplate;

    public OmnissaRestClient(OmnissaServer server) {
        this.server = server;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    private String cacheKey() {
        return server.getUrl() + "|" + server.getClientId();
    }

    /**
     * Overridable only so tests can point token fetches at a local, plain-HTTP
     * fixture instead of a real tenant. Production behavior is unchanged.
     */
    protected String tokenEndpoint() {
        return "https://" + server.getUrl() + "/SAAS/auth/oauthtoken";
    }

    /** Always hits the network — never reads or writes anything but this fetch's own result. */
    private CachedToken fetchToken() {
        String credentials = server.getClientId() + ":" + server.getClientSecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        ResponseEntity<Map> response = restTemplate.exchange(
                tokenEndpoint(),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        Map<?, ?> responseBody = response.getBody();
        String token = (String) responseBody.get("access_token");
        Object expiresIn = responseBody.get("expires_in");
        Duration ttl = (expiresIn instanceof Number n && n.longValue() > 0)
                ? Duration.ofSeconds(n.longValue())
                : DEFAULT_TTL;
        Duration effectiveTtl = ttl.compareTo(SAFETY_MARGIN) > 0 ? ttl.minus(SAFETY_MARGIN) : ttl;

        return new CachedToken(token, Instant.now().plus(effectiveTtl));
    }

    private String getAccessToken() {
        synchronized (TOKEN_CACHE) {
            CachedToken cached = TOKEN_CACHE.get(cacheKey());
            if (cached != null && cached.isFresh()) {
                return cached.value();
            }
            CachedToken fresh = fetchToken();
            TOKEN_CACHE.put(cacheKey(), fresh);
            return fresh.value();
        }
    }

    /**
     * Connectivity probe: always a live fetch, deliberately bypassing the
     * cache. This backs the reachability tile and health endpoints (already
     * memoized for 60s one layer up, in {@code TenantStatusService}) — if it
     * read the token cache instead, a healthy cached token could report the
     * tenant reachable for its whole TTL even after Access actually went
     * down, which is the one thing this probe exists to catch. The token it
     * fetches is still stored in the cache afterwards, so it isn't wasted.
     */
    public void checkToken() {
        synchronized (TOKEN_CACHE) {
            TOKEN_CACHE.put(cacheKey(), fetchToken());
        }
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
