package com.omnissa.access.approval.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the reverse-proxy allow-list in {@code docs/deployment.md} honest.
 *
 * <p><strong>Why this exists.</strong> A reverse proxy in front of this tool is
 * a security control: it decides what reaches an internal system at all, and it
 * is the only control still standing if the container is misconfigured or
 * exposes something a later release added. So it is default-deny — it
 * enumerates the valid paths and rejects everything else.
 *
 * <p>That is the right posture, and it has a cost: the list lives in a system
 * with no way of knowing when this application changes. A page added here but
 * not there fails in a way that is easy to miss — it works when clicked, because
 * React Router renders it client-side and the browser never asks the proxy, and
 * 404s only when the same URL is refreshed, bookmarked, or opened from a Slack
 * or Teams approval button. The server-side copy of this list drifted twice in
 * one release before it was removed.
 *
 * <p>The answer is not to widen the pattern; that would hand the whole decision
 * to the application. The answer is to stop relying on anyone remembering. This
 * test reads the routes the SPA actually declares and the pattern the
 * documentation publishes, and fails the build when the two disagree — so the
 * gap is found at release time rather than by an approver whose link 404s.
 */
class ProxyPatternCoverageTest {

    private static final Path APP_TSX = Path.of("src/main/frontend/src/App.tsx");
    private static final Path DEPLOYMENT_DOC = Path.of("docs/deployment.md");

    /** A concrete id, so a parameterised route is tested as a real URL would arrive. */
    private static final String SAMPLE_ID = "8f1c2d3e";

    @Test
    void everyClientRouteIsAllowedByTheDocumentedProxyPattern() throws IOException {
        Pattern proxy = Pattern.compile("^" + documentedProxyPattern() + "$");

        List<String> uncovered = new ArrayList<>();
        for (String route : declaredClientRoutes()) {
            if (!proxy.matcher(route).matches()) {
                uncovered.add(route);
            }
        }

        assertTrue(uncovered.isEmpty(),
                "These routes exist in App.tsx but are not permitted by the proxy pattern in "
                        + DEPLOYMENT_DOC + ": " + uncovered + ".\n"
                        + "They will work when clicked in the UI and 404 on refresh or from a chat "
                        + "deep link. Update the pattern in the documentation, then update the "
                        + "gateway. Do not widen the pattern to make this pass.");
    }

    /**
     * The paths the application needs that are not pages — the OAuth callback,
     * sign-out, the callout endpoint and static assets. Losing any of these
     * breaks sign-in or the Access integration rather than a page.
     */
    @Test
    void theSupportingPathsAreAllowedToo() throws IOException {
        Pattern proxy = Pattern.compile("^" + documentedProxyPattern() + "$");

        for (String required : List.of(
                "/login/oauth2/code/omnissa",        // OIDC redirect URI
                "/oauth2/authorization/omnissa",     // authorization request
                "/logout",
                "/api/approvals/new",                // the one internet-facing path
                "/api/approvals/stream",             // SSE, live queue updates
                "/requests/" + SAMPLE_ID,            // chat deep link target
                "/assets/index-abc123.js",
                "/favicon.ico")) {
            assertTrue(proxy.matcher(required).matches(),
                    required + " must be permitted by the proxy pattern in " + DEPLOYMENT_DOC);
        }
    }

    /**
     * Default-deny has to actually deny. If the documented pattern starts
     * admitting arbitrary paths it has stopped being a control, which is the
     * failure this whole approach exists to prevent.
     */
    @Test
    void thePatternStillRejectsUnknownPaths() throws IOException {
        Pattern proxy = Pattern.compile("^" + documentedProxyPattern() + "$");

        for (String shouldNotMatch : List.of(
                "/actuator/health",     // deliberately absent; the UAG monitor goes direct
                "/actuator/env",
                "/settings",            // no such route — removed from the pattern
                "/../etc/passwd",
                "/wp-login.php",
                "/some-page-nobody-added")) {
            assertFalse(proxy.matcher(shouldNotMatch).matches(),
                    shouldNotMatch + " is matched by the documented proxy pattern. A default-deny "
                            + "allow-list that admits unknown paths is not doing its job.");
        }
    }

    /** Route paths declared in the SPA router, normalised to concrete URLs. */
    private static List<String> declaredClientRoutes() throws IOException {
        String tsx = Files.readString(APP_TSX, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("path=\"([^\"]+)\"").matcher(tsx);

        List<String> routes = new ArrayList<>();
        while (m.find()) {
            String path = m.group(1);
            if (path.equals("*")) {
                // The catch-all renders in the browser only; it is deliberately
                // NOT something the proxy should admit.
                continue;
            }
            // Nested <Route path="dashboard"> children are relative to "/".
            String absolute = path.startsWith("/") ? path : "/" + path;
            routes.add(absolute.replaceAll(":[A-Za-z]+", SAMPLE_ID));
        }
        routes.add("/");   // the index route, declared as <Route index .../>

        if (routes.size() < 5) {
            fail("Parsed only " + routes + " from " + APP_TSX + ". The router's shape has "
                    + "changed and this test is no longer reading it — fix the parser rather "
                    + "than letting the check silently pass on nothing.");
        }
        return routes;
    }

    /**
     * The pattern published in the deployment guide — the one an operator is
     * told to paste into their gateway. Read from the document rather than
     * duplicated here, because a copy in the test would be a third place to
     * drift and would happily agree with itself while the documentation was
     * wrong.
     */
    private static String documentedProxyPattern() throws IOException {
        String doc = Files.readString(DEPLOYMENT_DOC, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("^\\((/\\|.*)\\)$", Pattern.MULTILINE).matcher(doc);
        if (!m.find()) {
            fail("Could not find the proxy allow-list pattern in " + DEPLOYMENT_DOC
                    + ". It is expected as a fenced line beginning \"(/|\". If the guide was "
                    + "restructured, update this test — do not delete it.");
        }
        return "(" + m.group(1) + ")";
    }
}
