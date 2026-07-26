package com.omnissa.access.approval.security;

import com.omnissa.access.approval.model.security.AuthorityName;
import com.omnissa.access.approval.repository.UserAccountRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Value("${omnissa.admin-oauth.client-id:}")
    private String adminClientId;

    @Value("${omnissa.api.username:}")
    private String apiUsername;

    @Value("${omnissa.api.password:}")
    private String apiPassword;

    @Value("${omnissa.auth.local-login-disabled:false}")
    private boolean localLoginDisabled;

    /** {@code <groupId>:<ROLE>} pairs matched against the OIDC group_ids claim. */
    @Value("${omnissa.rbac.role-map:}")
    private String roleMap;

    @Value("${omnissa.api.rate-limit:60}")
    private int apiRateLimit;

    /**
     * Per-IP rate limiting on the inbound callout endpoint. Registered at
     * HIGHEST_PRECEDENCE so cheap rejection happens before basic auth.
     * A no-op when omnissa.api.rate-limit is 0.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(apiRateLimit));
        // Both internet-facing, unauthenticated inbound endpoints (Access callout,
        // Slack interactions) get per-IP rate limiting.
        registration.addUrlPatterns("/api/approvals/new", "/api/slack/interactions");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Optional Basic auth on the inbound callout endpoint. Runs before the
     * Spring Security chain; a no-op when omnissa.api.username is blank.
     */
    @Bean
    public FilterRegistrationBean<ApiBasicAuthFilter> apiBasicAuthFilter() {
        FilterRegistrationBean<ApiBasicAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiBasicAuthFilter(apiUsername, apiPassword));
        registration.addUrlPatterns("/api/approvals/new");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations) throws Exception {
        http
            // CSRF: cookie-based so the SPA can read the token via JS and include it
            // as X-XSRF-TOKEN on mutating requests. The inbound callout endpoint is
            // excluded because Omnissa Access POSTs there without a browser session.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Inbound endpoints POSTed by external systems (no browser session):
                // Access callout + Slack interactions (Slack-signature authenticated).
                .ignoringRequestMatchers("/api/approvals/new", "/api/slack/interactions")
            )
            // Force the deferred CSRF token to resolve on every request so the
            // XSRF-TOKEN cookie is always present by the time the SPA needs it.
            .addFilterAfter(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                                FilterChain chain) throws ServletException, IOException {
                    CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
                    if (token != null) token.getToken();
                    chain.doFilter(req, res);
                }
            }, BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // SpaController serves routes via forward:/index.html; Spring Boot 3
                // authorizes FORWARD/ERROR dispatches too, which turned /login into a
                // redirect loop. Authorization already happened on the original request.
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                // Omnissa Access POSTs callout requests here — must be unauthenticated.
                // It also probes with OPTIONS when saving the approvals settings; a
                // redirect-to-login there reads as "Unable to connect to the URI".
                .requestMatchers(HttpMethod.POST, "/api/approvals/new").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/approvals/new").permitAll()
                // Slack interaction callback — unauthenticated at the session layer,
                // authenticated by Slack signature in SlackController (design §2).
                .requestMatchers(HttpMethod.POST, "/api/slack/interactions").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/slack/interactions").permitAll()
                // Static assets served by Vite build
                .requestMatchers("/assets/**", "/favicon.ico", "/favicon.svg",
                        "/apple-touch-icon.png", "/vite.svg").permitAll()
                // OpenAPI / Swagger UI
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Health probe for Docker
                .requestMatchers("/actuator/health").permitAll()
                // Public auth-mode discovery for the login page
                .requestMatchers("/api/config/auth").permitAll()
                // Login page and OAuth2 endpoints
                .requestMatchers("/login", "/login/**", "/oauth2/**").permitAll()

                // ---- Role-based rules (#52) ----------------------------------
                // Roles come from Omnissa Access group membership; see
                // GroupRoleMapper. Rules are centralised here rather than spread
                // across @PreAuthorize so the whole policy is reviewable at once.
                //
                // ADMIN    everything
                // APPROVER decide requests, revoke, block, allow re-request
                // VIEWER   read the queue, audit and statistics
                // AUDITOR  audit trail and CSV export only — no live queue
                //
                // ROLE_USER is the pre-RBAC legacy role and reads as VIEWER.

                // Anyone signed in may ask who they are.
                .requestMatchers("/api/auth/**").authenticated()

                // Tenant configuration, users, log bundle — admin only. The log
                // bundle is admin-gated because it contains request payloads.
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/logs/**").hasRole("ADMIN")
                .requestMatchers("/api/config/server/**", "/api/config/server").hasRole("ADMIN")

                // Auto-approval rules: readable by anyone who can see the queue,
                // writable only by admins — a rule change silently grants access.
                .requestMatchers(HttpMethod.GET, "/api/rules", "/api/rules/**")
                        .hasAnyRole("ADMIN", "APPROVER", "VIEWER", "USER")
                .requestMatchers("/api/rules", "/api/rules/**").hasRole("ADMIN")

                // Bulk export is an extraction, not a read: it produces a file that
                // leaves the application's controls entirely and can be retained or
                // shared without trace. Reading the trail on screen is page-by-page
                // and stays inside the session, so the two are gated differently —
                // a viewer may read the record but not take a copy of it.
                // These must precede the /api/audit/** read rule below; the first
                // matching rule wins.
                .requestMatchers("/api/audit/export.csv").hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/approvals/export.csv")
                        .hasAnyRole("ADMIN", "APPROVER", "AUDITOR")

                // Reading historical records — the auditor's whole remit.
                .requestMatchers("/api/audit", "/api/audit/**")
                        .hasAnyRole("ADMIN", "APPROVER", "VIEWER", "AUDITOR", "USER")

                // Deleting a local record, or purging remote state, is destructive
                // and unrelated to deciding a request.
                .requestMatchers(HttpMethod.DELETE, "/api/approvals/requests/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/approvals/remote").hasRole("ADMIN")

                // Decisions and entitlement changes.
                .requestMatchers(HttpMethod.POST,
                        "/api/approvals/response",
                        "/api/approvals/response/all",
                        "/api/approvals/pull",
                        "/api/approvals/requests/*/revoke",
                        "/api/approvals/requests/*/allow-rerequest")
                        .hasAnyRole("ADMIN", "APPROVER")

                // Reading the queue, catalog and statistics. Deliberately excludes
                // AUDITOR, whose remit is the record rather than the live queue.
                .requestMatchers("/api/approvals/**", "/api/catalogitems/**",
                        "/api/statistics/**", "/stats/**", "/api/config/status")
                        .hasAnyRole("ADMIN", "APPROVER", "VIEWER", "USER")

                .anyRequest().authenticated()
            )
            // OAuth2 login — only wired when an admin OAuth2 client-id is configured
            .oauth2Login(oauth2 -> {
                oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true)
                    .userInfoEndpoint(userInfo -> userInfo
                        .oidcUserService(oidcUserService())
                    );
                // Omnissa Access enforces PKCE even for confidential clients; Spring
                // only sends code_challenge for public clients unless opted in here.
                ClientRegistrationRepository repo = clientRegistrations.getIfAvailable();
                if (repo != null) {
                    var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                        repo, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
                    resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
                    oauth2.authorizationEndpoint(authz -> authz.authorizationRequestResolver(resolver));
                }
            });
        // Form login — local username/password fallback. Skipped entirely in
        // OAuth-only mode so POST /login/local no longer authenticates.
        if (!localLoginDisabled) {
            http.formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login/local")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error=true")
                .permitAll()
            );
        }
        http
            .logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern("/logout"))
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )
            // API calls from the SPA get a JSON status, browser navigations get a
            // redirect. Previously everything redirected, so an expired session
            // returned 302 -> /login -> 200 HTML and the SPA saw res.ok === true
            // with an HTML body: a logged-out user looked like a successful,
            // empty response. Order matters — the /api/** rule must be
            // registered before the catch-all.
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    jsonEntryPoint(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"),
                    PathPatternRequestMatcher.pathPattern("/api/**")
                )
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    PathPatternRequestMatcher.pathPattern("/**")
                )
                // Without this, a denied request falls through to Boot's
                // BasicErrorController and — because fetch sends Accept: */* —
                // returns the Whitelabel HTML page, so res.json() throws on
                // every 403.
                .accessDeniedHandler((request, response, denied) ->
                    writeJsonStatus(response, HttpServletResponse.SC_FORBIDDEN,
                            "You do not have permission to do that"))
            )
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(63072000)
                    .preload(true)
                )
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            );
        return http.build();
    }

    /**
     * Grants application roles from Omnissa Access group membership.
     *
     * <p>The delegate fetches userinfo and merges it into the claims, which is
     * essential rather than incidental: Access serves {@code group_ids} as an
     * <em>overflow claim</em> ({@code ovc}/{@code ovl} in the token) once the
     * list grows, so the ID token alone does not carry it.
     *
     * <p>Every signed-in user keeps their OIDC authorities and gains at least
     * {@link GroupRoleMapper#DEFAULT_ROLE}. With no {@code omnissa.rbac.role-map}
     * configured, that is all anyone gets — deliberately, so enabling the map is
     * the explicit act that grants privilege.
     */
    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        Map<String, AuthorityName> parsedRoleMap = GroupRoleMapper.parse(roleMap);

        if (parsedRoleMap.isEmpty()) {
            logger.warn("No omnissa.rbac.role-map configured — every OIDC user gets {} only.",
                    GroupRoleMapper.DEFAULT_ROLE);
        } else {
            logger.info("Group role mapping active for {} group(s)", parsedRoleMap.size());
        }

        return userRequest -> {
            OidcUser user = delegate.loadUser(userRequest);

            List<String> groupIds = GroupRoleMapper.groupIdsFrom(user.getClaim("group_ids"));
            Set<AuthorityName> roles = GroupRoleMapper.rolesFor(parsedRoleMap, groupIds);

            Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(role.name())));

            String identity = user.getEmail() != null ? user.getEmail() : user.getSubject();
            logger.info("OIDC login: {} in {} group(s) -> {}", identity, groupIds.size(), roles);

            String conflict = GroupRoleMapper.auditorConflict(roles);
            if (conflict != null) {
                logger.warn("Role conflict for {}: {}", identity, conflict);
            }

            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
        };
    }

    /** Entry point that answers with a JSON status instead of a login redirect. */
    private static AuthenticationEntryPoint jsonEntryPoint(int status, String message) {
        return (request, response, authException) -> writeJsonStatus(response, status, message);
    }

    private static void writeJsonStatus(HttpServletResponse response, int status, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\""
                + message.replace("\"", "\\\"") + "\"}");
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            var user = userAccountRepository.findByUsername(username);
            if (user == null) throw new UsernameNotFoundException("User not found: " + username);
            return user;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
