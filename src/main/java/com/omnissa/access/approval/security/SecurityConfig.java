package com.omnissa.access.approval.security;

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
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
        registration.addUrlPatterns("/api/approvals/new");
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
                .ignoringRequestMatchers("/api/approvals/new")
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
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )
            // API calls from SPA get 401, not a redirect
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new AntPathRequestMatcher("/**")
                )
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

    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        return new OidcUserService();
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
