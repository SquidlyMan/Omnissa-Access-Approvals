package com.omnissa.access.approval.security;

import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Where the browser goes after a local sign-in.
 *
 * <p>Spring Security remembers the page a user was heading for when the login
 * page interrupted them, so a chat deep link such as
 * {@code /requests/42?action=approve} survives the round-trip. The saved
 * request's redirect URL is rebuilt from that request, which makes it
 * request-derived data: a scanner (CodeQL, {@code java/unvalidated-url-redirection})
 * cannot tell it from a URL an attacker chose, and the success handler used
 * to pass it to {@code sendRedirect} as it was. This class makes the intent
 * checkable. Only the saved request's path and query are used, and only when
 * the URL names this site and the path is a plain absolute path — never
 * another host, never a scheme-relative {@code //host}, never a character
 * outside what RFC 3986 allows there. Everything else goes to the Dashboard,
 * which is where a sign-in with nothing saved always went.
 */
final class PostLoginDestination {

    static final String DASHBOARD = "/";

    /**
     * An absolute path with an optional query, in RFC 3986's pchar alphabet
     * plus "/" and "?", whose second character is not another "/" — so a
     * scheme-relative URL can never pass as a path.
     */
    private static final Pattern SAME_SITE_PATH = Pattern.compile(
            "/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-][A-Za-z0-9._~!$&'()*+,;=:@%/-]*)?"
            + "(?:\\?[A-Za-z0-9._~!$&'()*+,;=:@%/?-]*)?");

    private PostLoginDestination() {
    }

    /**
     * The destination for a saved request, or the Dashboard when there is
     * none worth honouring.
     *
     * @param saved      what the request cache holds, or {@code null}
     * @param serverName the host the sign-in request itself arrived on
     */
    static String resolve(SavedRequest saved, String serverName) {
        return saved == null ? DASHBOARD : resolve(saved.getRedirectUrl(), serverName);
    }

    /**
     * @param redirectUrl the saved request's redirect URL, absolute as Spring
     *                    Security rebuilds it
     * @param serverName  the host the sign-in request itself arrived on
     */
    static String resolve(String redirectUrl, String serverName) {
        if (redirectUrl == null) {
            return DASHBOARD;
        }
        URI uri;
        try {
            uri = new URI(redirectUrl);
        } catch (URISyntaxException e) {
            return DASHBOARD;
        }
        if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(serverName)) {
            return DASHBOARD;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return DASHBOARD;
        }
        String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        if (SAME_SITE_PATH.matcher(target).matches()) {
            return target;
        }
        return DASHBOARD;
    }
}
