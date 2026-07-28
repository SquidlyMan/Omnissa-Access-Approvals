package com.omnissa.access.approval.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Serves the SPA shell for client-side routes, so a deep link, refresh or
 * bookmark works rather than 404ing.
 *
 * <p>This used to be a hand-written list of paths mirroring the routes in
 * {@code App.tsx}. Two declarations of the same thing drift: adding a page to
 * the router without adding it here produced a route that worked when navigated
 * to in-app but 404d on refresh — which is how {@code /users} shipped broken —
 * and by the time that was noticed the list had also drifted the other way,
 * still forwarding a {@code /settings} route the SPA had never had. The failure
 * is worse than a missing page: Slack and Teams approval buttons are deep links
 * of the form {@code /requests/{id}?action=approve}, so a route the backend
 * does not forward silently breaks chat approvals.
 *
 * <p>So nothing here enumerates client routes. Instead the SPA shell is the
 * <em>fallback</em>: every other handler is given the request first, and only
 * when none of them wants it — the point at which Spring is about to answer
 * 404 — does it become a candidate for the shell. Adding a page to the router
 * therefore needs no backend change at all.
 *
 * <p>Being a fallback is what makes this safe. {@code /api/**} reaches its
 * controllers, {@code /actuator/health} reaches the actuator mapping,
 * {@code /oauth2/**}, {@code /login/oauth2/**} and {@code /logout} are consumed
 * by the security filter chain before MVC sees them, and {@code /assets/**},
 * {@code /favicon.ico} and {@code /index.html} are served by the static
 * resource handler. None of those ever reach the fallback, because reaching it
 * means nothing matched. The two guards below only decide what happens to the
 * requests that are already going to 404.
 */
@Controller
@ControllerAdvice
public class SpaController {

    private static final String SPA_SHELL = "forward:/index.html";

    /**
     * Path prefixes whose 404 is a real 404 and must stay one.
     *
     * <p>These are backend surfaces, not pages. An unknown {@code /api/**} path
     * must answer 404 (or the security chain's JSON 401/403) rather than an
     * HTML shell with status 200 — the SPA checks {@code res.ok} and would read
     * a page of markup as a successful, empty response. {@code /actuator} is
     * listed for the same reason and one blunter one: Docker, CasaOS and the
     * UAG health monitor act on a failure there, and CasaOS <em>recreates the
     * container</em>, so an actuator path must never answer 200-with-HTML
     * merely because the endpoint was not exposed.
     *
     * <p>The security-filter paths are listed even though they normally never
     * reach MVC, because they do when OAuth2 login is not configured — an
     * unconfigured {@code /oauth2/authorization/omnissa} should say "not here",
     * not hand back the application.
     */
    private static final List<String> BACKEND_PREFIXES = List.of(
            "/api", "/actuator", "/error", "/oauth2", "/login/oauth2", "/logout",
            "/swagger-ui", "/v3/api-docs");

    /**
     * The application root. Spring Boot's welcome-page mapping would also
     * forward {@code /} to index.html, but only while index.html happens to be
     * on the resource path; mapping it explicitly keeps the entry point of the
     * whole tool from depending on that.
     */
    @GetMapping("/")
    public String root() {
        return SPA_SHELL;
    }

    /**
     * Last stop before a 404. {@link NoResourceFoundException} is what the
     * static resource handler — the lowest-precedence mapping there is —
     * throws when it has run out of places to look, so anything arriving here
     * has already been declined by every controller, the actuator and the
     * resource handler itself.
     *
     * <p>Re-throwing rather than answering is deliberate: this method decides
     * only whether a 404 should instead be the SPA, and when it should not, the
     * exception continues to Spring's default handling untouched.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public String spaShell(NoResourceFoundException notFound, HttpServletRequest request)
            throws NoResourceFoundException {
        if (!isClientRoute(request)) {
            throw notFound;
        }
        return SPA_SHELL;
    }

    /**
     * Whether an otherwise-unmatched request looks like someone opening a page.
     *
     * <p>Three conditions, none of which name a route:
     * <ol>
     *   <li>It is a GET or HEAD. A POST to an unknown path is a mistake or a
     *       probe, and answering it with an HTML page hides that.</li>
     *   <li>It is not under a backend prefix — see {@link #BACKEND_PREFIXES}.</li>
     *   <li>Its last segment has no file extension. Client routes are words
     *       ({@code /queue}, {@code /requests/42}); assets are files
     *       ({@code /assets/index-a1b2c3.js}). A missing asset must 404 loudly:
     *       serving the shell in its place turns a broken build into a page
     *       that renders and then fails at runtime, and a script tag that
     *       receives HTML fails in a way that names neither file.</li>
     * </ol>
     */
    private static boolean isClientRoute(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return false;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String prefix : BACKEND_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }

        return path.substring(path.lastIndexOf('/') + 1).indexOf('.') < 0;
    }
}
