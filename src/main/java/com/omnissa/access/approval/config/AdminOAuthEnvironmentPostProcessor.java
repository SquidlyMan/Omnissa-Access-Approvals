package com.omnissa.access.approval.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contributes the Spring Security OAuth2 client registration for Omnissa Access
 * — but only when there is actually a client to register.
 *
 * <p><strong>Why this is not simply lines in application.properties.</strong>
 * It used to be, written as
 * {@code ...registration.omnissa.client-id=${omnissa.admin-oauth.client-id:}}
 * so that a blank client id would "disable" OAuth2 login. A placeholder with an
 * empty default cannot omit a key, only give it an empty value, so Spring Boot
 * bound a registration whose client id was empty and
 * {@code OAuth2ClientProperties.validate()} refused to start the application:
 * <em>"Client id of registration 'omnissa' must not be empty"</em>. Since the
 * shipped default is blank, that was the out-of-the-box state — a fresh install
 * with no tenant could not boot at all. Reordering or re-defaulting the
 * properties cannot fix it; the keys have to be <em>absent</em>, which is what
 * this class arranges. Boot's {@code ClientsConfiguredCondition} then finds no
 * registrations, skips the whole OAuth2 client auto-configuration, and no
 * {@code ClientRegistrationRepository} bean exists — which is the signal
 * {@code SecurityConfig} uses to decide whether to wire {@code oauth2Login}.
 *
 * <p><strong>Nothing here overrides an operator.</strong> The generated
 * properties are added at the <em>lowest</em> precedence, so an explicit
 * {@code spring.security.oauth2.client.*} value — in the environment, in
 * {@code application-local.properties}, anywhere — still wins, exactly as it did
 * when these lines lived in {@code application.properties}. The values and
 * defaults below are deliberately identical to the ones they replace: the
 * configured deployment must see the same registration it saw before.
 *
 * <p>Runs last of the environment post-processors so that
 * {@code ConfigDataEnvironmentPostProcessor} has already loaded
 * {@code application.properties} and any profile-specific files by the time the
 * {@code omnissa.admin-oauth.*} values are read.
 */
public class AdminOAuthEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * Fixed, and depended on in several places: the redirect URI registered on
     * the Access client ends {@code /login/oauth2/code/omnissa}, so renaming it
     * breaks sign-in at the tenant rather than here.
     */
    public static final String REGISTRATION_ID = "omnissa";

    private static final String REGISTRATION =
            "spring.security.oauth2.client.registration." + REGISTRATION_ID + ".";
    private static final String PROVIDER =
            "spring.security.oauth2.client.provider." + REGISTRATION_ID + ".";

    private static final String PROPERTY_SOURCE_NAME = "omnissaAdminOAuthClient";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!clientIdConfigured(environment) || !providerConfigured(environment)) {
            // A client id with no provider is a half-configuration, and used to
            // fail startup with "issuer cannot be empty". Contributing nothing
            // leaves the tool running on local sign-in; SecurityConfig logs what
            // is missing, because it can tell the two cases apart.
            return;
        }

        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put(REGISTRATION + "client-id",
                environment.getProperty("omnissa.admin-oauth.client-id"));
        registration.put(REGISTRATION + "client-secret",
                environment.getProperty("omnissa.admin-oauth.client-secret", ""));
        registration.put(REGISTRATION + "redirect-uri",
                environment.getProperty("omnissa.admin-oauth.redirect-uri",
                        "http://localhost:8081/login/oauth2/code/omnissa"));
        // The "group" scope is what makes Access emit the group_names /
        // group_ids claims that roles are resolved from. Drop it and every user
        // silently becomes a Viewer, with nothing in the logs to say why.
        registration.put(REGISTRATION + "scope",
                environment.getProperty("omnissa.admin-oauth.scope", "openid,email,profile,group"));
        registration.put(REGISTRATION + "authorization-grant-type", "authorization_code");
        registration.put(REGISTRATION + "client-name", "Omnissa Access");

        String issuerUri = environment.getProperty("omnissa.admin-oauth.issuer-uri");
        if (StringUtils.hasText(issuerUri)) {
            // Option A: Spring fetches <issuer>/.well-known/openid-configuration
            // at startup. Omitted when blank so that Option B — the manually
            // spelled-out endpoints — is reachable without this fighting it.
            registration.put(PROVIDER + "issuer-uri", issuerUri);
        }

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, registration));
    }

    /** Whether an admin OIDC client has been named at all. */
    public static boolean clientIdConfigured(Environment environment) {
        return StringUtils.hasText(environment.getProperty("omnissa.admin-oauth.client-id"));
    }

    /**
     * Whether the tenant's OIDC endpoints can be resolved — by discovery from
     * an issuer, or from endpoints spelled out by hand (the documented Option B
     * fallback for tenants whose discovery document is unusable).
     */
    public static boolean providerConfigured(Environment environment) {
        return StringUtils.hasText(environment.getProperty("omnissa.admin-oauth.issuer-uri"))
                || StringUtils.hasText(environment.getProperty(PROVIDER + "issuer-uri"))
                || StringUtils.hasText(environment.getProperty(PROVIDER + "authorization-uri"));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
