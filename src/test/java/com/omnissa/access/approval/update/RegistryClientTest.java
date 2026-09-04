package com.omnissa.access.approval.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The registry call (#83, acceptance criterion 3).
 *
 * <p>Uses a fixture rather than the live registry so the pagination case is
 * deterministic: the point is that {@code tags/list} truncates at 100 with no
 * indication, so the request has to ask for more up front.
 */
class RegistryClientTest {

    private static final String REPO = "example/app";

    private RestTemplate template = new RestTemplate();
    private MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    private RegistryClient client = new RegistryClient(REPO, template);

    @Test
    @DisplayName("asks for the whole list in one page — the registry truncates at 100 silently")
    void requestsAPageLargerThanTheRegistryDefault() {
        // 197 tags, like the real repository the day this was written. A client
        // on the default page size would see 100 and never know.
        List<String> tags = IntStream.range(0, 197).mapToObj(i -> "1." + (i / 10) + "." + (i % 10)).toList();
        String body = "{\"tags\":[" + tags.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",")) + "]}";

        server.expect(requestTo("https://ghcr.io/token?scope=repository:" + REPO + ":pull"))
                .andRespond(withSuccess("{\"token\":\"anon\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE))
                .andExpect(queryParam("n", String.valueOf(RegistryClient.PAGE_SIZE)))
                .andExpect(header("Authorization", "Bearer anon"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.listTags()).hasSize(197).contains("1.19.6");
        assertThat(RegistryClient.PAGE_SIZE).as("must exceed the registry's silent 100-tag cap").isGreaterThan(100);
        server.verify();
    }

    @Test
    @DisplayName("a registry failure throws — the caller decides how soft to fail")
    void failureThrows() {
        server.expect(requestTo("https://ghcr.io/token?scope=repository:" + REPO + ":pull"))
                .andRespond(withServerError());
        assertThatThrownBy(client::listTags).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a token response with no token is an error, not an empty tag list")
    void missingTokenIsAnError() {
        server.expect(requestTo("https://ghcr.io/token?scope=repository:" + REPO + ":pull"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::listTags).hasMessageContaining("no token");
    }

    private void token() {
        server.expect(requestTo("https://ghcr.io/token?scope=repository:" + REPO + ":pull"))
                .andRespond(withSuccess("{\"token\":\"anon\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a body that is not JSON is an error, not an empty list")
    void malformedJsonIsAnError() {
        token();
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE))
                .andRespond(withSuccess("<html>captive portal</html>", MediaType.TEXT_HTML));
        assertThatThrownBy(client::listTags).hasMessageContaining("unreadable");
    }

    @Test
    @DisplayName("\"tags\": null is an empty list — the caller decides what that means")
    void nullTagsIsEmpty() {
        token();
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE))
                .andRespond(withSuccess("{\"name\":\"" + REPO + "\",\"tags\":null}", MediaType.APPLICATION_JSON));
        assertThat(client.listTags()).isEmpty();
    }

    @Test
    @DisplayName("a body over the cap is refused before it is parsed — or buffered")
    void oversizedBodyIsRefused() {
        token();
        String huge = "{\"tags\":[\"" + "x".repeat(RegistryClient.MAX_BODY_BYTES + 10) + "\"]}";
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE))
                .andRespond(withSuccess(huge, MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::listTags).hasMessageContaining("exceeded");
    }

    @Test
    @DisplayName("a registry that pages anyway is followed — a first page alone under-reports for ever")
    void followsPagination() {
        token();
        HttpHeaders link = new HttpHeaders();
        link.add("Link", "</v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE + "&last=1.9.9>; rel=\"next\"");
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE))
                .andRespond(withSuccess("{\"tags\":[\"1.9.8\",\"1.9.9\"]}", MediaType.APPLICATION_JSON).headers(link));
        server.expect(requestTo("https://ghcr.io/v2/" + REPO + "/tags/list?n=" + RegistryClient.PAGE_SIZE + "&last=1.9.9"))
                .andExpect(header("Authorization", "Bearer anon"))
                .andRespond(withSuccess("{\"tags\":[\"1.10.0\"]}", MediaType.APPLICATION_JSON));
        assertThat(client.listTags()).containsExactly("1.9.8", "1.9.9", "1.10.0");
        server.verify();
    }

    @Test
    @DisplayName("a redirect is refused rather than followed with the token")
    void redirectIsRefused() {
        server.expect(requestTo("https://ghcr.io/token?scope=repository:" + REPO + ":pull"))
                .andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://evil.example/token"));
        assertThatThrownBy(client::listTags).hasMessageContaining("redirected");
    }

    @Test
    @DisplayName("a repository that is not owner/name falls back to the default instead of failing startup")
    void invalidRepositoryFallsBack() {
        assertThat(RegistryClient.validRepository("example/app")).isEqualTo("example/app");
        assertThat(RegistryClient.validRepository("Example/App")).isEqualTo(RegistryClient.DEFAULT_REPOSITORY);
        assertThat(RegistryClient.validRepository("a/b?x=1")).isEqualTo(RegistryClient.DEFAULT_REPOSITORY);
        assertThat(RegistryClient.validRepository("a/b/../../c")).isEqualTo(RegistryClient.DEFAULT_REPOSITORY);
        assertThat(RegistryClient.validRepository("{x}/y")).isEqualTo(RegistryClient.DEFAULT_REPOSITORY);
        assertThat(RegistryClient.validRepository(null)).isEqualTo(RegistryClient.DEFAULT_REPOSITORY);
    }
}
