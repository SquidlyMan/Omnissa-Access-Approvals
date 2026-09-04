package com.omnissa.access.approval.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
