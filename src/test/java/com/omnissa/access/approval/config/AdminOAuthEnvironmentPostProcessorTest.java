package com.omnissa.access.approval.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one rule that makes OAuth2 optional at all: the registration keys
 * must be <em>absent</em>, not empty.
 *
 * <p>An assertion that the client id is blank would pass against the code this
 * replaced, which is exactly why every case below asks whether the property
 * <em>exists</em>. Spring Boot's {@code ClientsConfiguredCondition} binds the
 * whole {@code spring.security.oauth2.client.registration} map and treats one
 * key as one client, so a present-but-empty value is indistinguishable from a
 * configured client until validation rejects it and the application fails to
 * start.
 */
class AdminOAuthEnvironmentPostProcessorTest {

    private static final String CLIENT_ID =
            "spring.security.oauth2.client.registration.omnissa.client-id";
    private static final String SCOPE =
            "spring.security.oauth2.client.registration.omnissa.scope";
    private static final String ISSUER_URI =
            "spring.security.oauth2.client.provider.omnissa.issuer-uri";

    private final AdminOAuthEnvironmentPostProcessor processor =
            new AdminOAuthEnvironmentPostProcessor();

    @Test
    @DisplayName("no client id: not one OAuth2 client property is defined")
    void blankClientIdContributesNothing() {
        ConfigurableEnvironment environment = process(new MockEnvironment()
                .withProperty("omnissa.admin-oauth.client-id", ""));

        assertThat(environment.containsProperty(CLIENT_ID)).isFalse();
    }

    @Test
    @DisplayName("a client id with no tenant endpoints is refused rather than half-registered")
    void clientIdWithoutAProviderContributesNothing() {
        // This combination used to abort startup with "issuer cannot be empty".
        // Contributing nothing leaves the tool running on local sign-in;
        // SecurityConfig logs which property is missing.
        ConfigurableEnvironment environment = process(new MockEnvironment()
                .withProperty("omnissa.admin-oauth.client-id", "ApprovalAdmin")
                .withProperty("omnissa.admin-oauth.issuer-uri", ""));

        assertThat(environment.containsProperty(CLIENT_ID)).isFalse();
    }

    @Test
    @DisplayName("a client id and an issuer produce the registration, group scope included")
    void discoveryConfigurationContributesTheRegistration() {
        ConfigurableEnvironment environment = process(new MockEnvironment()
                .withProperty("omnissa.admin-oauth.client-id", "ApprovalAdmin")
                .withProperty("omnissa.admin-oauth.issuer-uri", "https://tenant.invalid/SAAS/auth"));

        assertThat(environment.getProperty(CLIENT_ID)).isEqualTo("ApprovalAdmin");
        assertThat(environment.getProperty(ISSUER_URI)).isEqualTo("https://tenant.invalid/SAAS/auth");
        // Roles are resolved from the group claim; drop this default and every
        // user silently becomes a Viewer.
        assertThat(environment.getProperty(SCOPE)).isEqualTo("openid,email,profile,group");
    }

    @Test
    @DisplayName("manually configured endpoints work without an issuer, and discovery stays off")
    void manualProviderConfigurationContributesNoIssuer() {
        // Option B, for tenants whose discovery document is unusable. An issuer
        // contributed here as an empty string would send Spring to fetch
        // /.well-known/openid-configuration from nowhere.
        ConfigurableEnvironment environment = process(new MockEnvironment()
                .withProperty("omnissa.admin-oauth.client-id", "ApprovalAdmin")
                .withProperty("spring.security.oauth2.client.provider.omnissa.authorization-uri",
                        "https://tenant.invalid/SAAS/auth/oauth2/authorize"));

        assertThat(environment.getProperty(CLIENT_ID)).isEqualTo("ApprovalAdmin");
        assertThat(environment.containsProperty(ISSUER_URI)).isFalse();
    }

    @Test
    @DisplayName("an explicitly set property outranks the generated one")
    void explicitConfigurationWins() {
        // The generated properties are added at the lowest precedence, so the
        // escape hatch that existed while these lines lived in
        // application.properties still exists.
        ConfigurableEnvironment environment = process(new MockEnvironment()
                .withProperty("omnissa.admin-oauth.client-id", "ApprovalAdmin")
                .withProperty("omnissa.admin-oauth.issuer-uri", "https://tenant.invalid/SAAS/auth")
                .withProperty(SCOPE, "openid,email,profile"));

        assertThat(environment.getProperty(SCOPE)).isEqualTo("openid,email,profile");
    }

    private ConfigurableEnvironment process(ConfigurableEnvironment environment) {
        processor.postProcessEnvironment(environment, null);
        return environment;
    }
}
