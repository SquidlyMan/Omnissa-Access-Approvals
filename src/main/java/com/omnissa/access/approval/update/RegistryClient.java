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

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
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

    private static final Logger logger = LoggerFactory.getLogger(RegistryClient.class);

    static final int PAGE_SIZE = 1000;
    /** A registry answer is a few kilobytes; a megabyte is already an incident, not a tag list. */
    static final int MAX_BODY_BYTES = 1_000_000;
    /** Pages are followed, but not for ever — a registry that keeps saying "next" is not answering. */
    static final int MAX_PAGES = 10;
    static final String DEFAULT_REPOSITORY = "squidlyman/omnissa-access-approvals";
    /** GHCR names: lowercase owner/name, dots, dashes and underscores between alphanumerics. */
    static final Pattern REPOSITORY = Pattern.compile("^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+$");

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

    private final String repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RegistryClient(@Value("${omnissa.update.registry-repo:squidlyman/omnissa-access-approvals}")
                          String repository) {
        this(validRepository(repository), defaultRestTemplate());
    }

    RegistryClient(String repository, RestTemplate restTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
    }

    /**
     * The repository goes straight into a URL. A value with a query string, a
     * traversal, or a brace (which RestTemplate would read as a URI template)
     * would fail every check with a message about something else; refuse it
     * at startup and fall back, loudly, rather than fail the application over
     * an update-check setting.
     */
    static String validRepository(String configured) {
        String candidate = configured == null ? "" : configured.trim();
        if (REPOSITORY.matcher(candidate).matches()) {
            return candidate;
        }
        logger.warn("OMNISSA_UPDATE_REGISTRY_REPO '{}' is not an owner/name repository; watching {} instead",
                configured, DEFAULT_REPOSITORY);
        return DEFAULT_REPOSITORY;
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // A redirect would carry the bearer token to wherever it points.
                // The token is an anonymous pull scope, but there is no reason
                // to hand it to anyone: ghcr.io answers these URLs directly.
                connection.setInstanceFollowRedirects(false);
            }
        };
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

    /** One registry answer: its body, and where the next page is, if the registry said so. */
    private record Page(String body, String next) {
    }

    /**
     * Every tag the registry lists. Asks for {@link #PAGE_SIZE} per page and
     * follows {@code Link: rel="next"} if the registry pages anyway — a
     * checker that stops at the first page under-reports for ever and never
     * errors, which is the failure this class exists to avoid.
     *
     * @throws RuntimeException on any failure; the caller decides how soft to fail
     */
    public List<String> listTags() {
        String token = fetchToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        List<String> tags = new ArrayList<>();
        String url = tagsUrl();
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            Page answer = get(url, headers);
            tags.addAll(parseTags(answer.body()));
            url = answer.next();
        }
        if (url != null) {
            throw new IllegalStateException("Registry kept paginating beyond " + MAX_PAGES + " pages; refusing to guess at the rest");
        }
        return tags;
    }

    private List<String> parseTags(String body) {
        List<String> tags = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(body == null || body.isBlank() ? "{}" : body).path("tags");
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
        String body = get(tokenUrl(), new HttpHeaders()).body();
        try {
            String token = mapper.readTree(body == null || body.isBlank() ? "{}" : body).path("token").asText(null);
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

    /**
     * GET with the body read through a size cap. {@code String.class} would
     * buffer whatever arrives; a registry — or whatever is answering in its
     * name — that streams a large body slowly would take the check's thread
     * and then the heap with it.
     */
    private Page get(String url, HttpHeaders headers) {
        return restTemplate.execute(URI.create(url), HttpMethod.GET,
                request -> request.getHeaders().addAll(headers),
                RegistryClient::readPage);
    }

    private static Page readPage(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is3xxRedirection()) {
            throw new IllegalStateException("Registry redirected to " + response.getHeaders().getFirst("Location")
                    + "; refusing to follow");
        }
        String body = new String(readBounded(response.getBody()), StandardCharsets.UTF_8);
        return new Page(body, nextLink(response.getHeaders().getFirst("Link")));
    }

    static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_BODY_BYTES) {
                throw new IllegalStateException("Registry response exceeded " + MAX_BODY_BYTES + " bytes; refusing to read it");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** {@code Link: </v2/x/tags/list?n=1000&last=1.9.9>; rel="next"} → an absolute URL, or null. */
    static String nextLink(String linkHeader) {
        if (linkHeader == null) {
            return null;
        }
        Matcher m = NEXT_LINK.matcher(linkHeader);
        if (!m.find()) {
            return null;
        }
        String target = m.group(1);
        return target.startsWith("/") ? "https://ghcr.io" + target : target;
    }
}
