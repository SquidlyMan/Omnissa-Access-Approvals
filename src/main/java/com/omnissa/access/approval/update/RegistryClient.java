package com.omnissa.access.approval.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the tags a public GHCR repository publishes.
 *
 * <p>Two things about the registry API are not obvious and both were learned
 * the hard way:
 *
 * <ul>
 *   <li><strong>{@code tags/list} truncates at 100 with no indication.</strong>
 *       The repository has nearly two hundred tags. Without {@code ?n=1000} the
 *       first page came back short, the newest minor line was absent from it,
 *       and a tag that {@code GET /manifests/} resolved perfectly well appeared
 *       not to exist. A checker that misses this under-reports forever and
 *       never errors. See {@link #PAGE_SIZE}.</li>
 *   <li><strong>The repository is public but the API still wants a bearer
 *       token.</strong> An anonymous token is issued on request for the pull
 *       scope; no credential is involved.</li>
 * </ul>
 *
 * <p>Timeouts are explicit and short. This client runs on the update check's
 * own scheduler thread precisely so a slow registry cannot stall anything
 * else, but a hung socket would still hold that thread until the next
 * container restart.
 */
@Component
public class RegistryClient {

    /**
     * Well above the tag count, so one page is the whole list. The registry's
     * default page is 100 and it does not say when it has cut the list short.
     */
    static final int PAGE_SIZE = 1000;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final String repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RegistryClient(@Value("${omnissa.update.registry-repo:squidlyman/omnissa-access-approvals}")
                          String repository) {
        this(repository, defaultRestTemplate());
    }

    /** Package-private so tests can hand in a template pointed at a stub. */
    RegistryClient(String repository, RestTemplate restTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public String repository() {
        return repository;
    }

    String tokenUrl() {
        return "https://ghcr.io/token?scope=repository:" + repository + ":pull";
    }

    String tagsUrl() {
        return "https://ghcr.io/v2/" + repository + "/tags/list?n=" + PAGE_SIZE;
    }

    /** Every tag the registry lists, unfiltered. Throws on any failure — callers decide how soft to fail. */
    public List<String> listTags() {
        String token = fetchToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate.exchange(tagsUrl(), HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();

        List<String> tags = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(body == null ? "{}" : body).path("tags");
            node.forEach(tag -> {
                if (tag.isTextual()) {
                    tags.add(tag.asText());
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Registry returned an unreadable tag list: " + e.getMessage(), e);
        }
        return tags;
    }

    private String fetchToken() {
        String body = restTemplate.getForObject(tokenUrl(), String.class);
        try {
            String token = mapper.readTree(body == null ? "{}" : body).path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Registry issued no token for the pull scope");
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Registry token response was unreadable: " + e.getMessage(), e);
        }
    }
}
