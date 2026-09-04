package com.omnissa.access.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment variable names in the documentation must actually bind.
 *
 * <p>Every setting is documented as an environment variable and implemented as a
 * dotted property, and the two are connected by Spring's relaxed binding rather
 * than by anything either file states. That connection is easy to get wrong in a
 * way nothing catches: a variable that does not map is not an error, it is
 * silently the default. The operator sets it, the application ignores it, and
 * both look fine.
 *
 * <p>This project has already shipped that exact failure once — the
 * configuration reference documented a blank client-id as the way to run
 * local-only, and that configuration would not start (#67). "The documented path
 * and the code must agree" is the lesson, and here it is enforced.
 */
class EnvironmentVariableNamesTest {

    private String bind(String variable, String value, String property) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test", Map.of(variable, (Object) value)));
        return Binder.get(environment).bind(property, String.class).orElse(null);
    }

    @Test
    @DisplayName("the callout credentials bind from their documented names")
    void calloutCredentials() {
        assertThat(bind("OMNISSA_API_USERNAME", "access", "omnissa.api.username"))
                .isEqualTo("access");
        assertThat(bind("OMNISSA_API_PASSWORD", "secret", "omnissa.api.password"))
                .isEqualTo("secret");
    }

    @Test
    @DisplayName("the anonymous-ingest acknowledgement binds from its documented name")
    void allowUnauthenticated() {
        assertThat(bind("OMNISSA_API_ALLOW_UNAUTHENTICATED", "true",
                "omnissa.api.allow-unauthenticated"))
                .as("if this does not bind, the acknowledgement is ignored and the "
                        + "application refuses to start with no way for the operator to "
                        + "override it")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("the trusted-proxy hop count binds from its documented name")
    void trustedProxyHops() {
        assertThat(bind("OMNISSA_SECURITY_TRUSTED_PROXY_HOPS", "2",
                "omnissa.security.trusted-proxy-hops"))
                .as("a hop count that does not bind leaves the deployment on the default "
                        + "of 0 while the operator believes it is configured — rate limits "
                        + "and login throttling would quietly stay shared")
                .isEqualTo("2");
    }

    @Test
    @DisplayName("the name without the section prefix does NOT bind")
    void theShorterNameIsNotTheName() {
        // Recorded because it is the name that reads naturally and was very nearly
        // documented. Spring maps underscores to the dots and dashes of the whole
        // property path; dropping a path segment does not resolve.
        assertThat(bind("OMNISSA_TRUSTED_PROXY_HOPS", "2",
                "omnissa.security.trusted-proxy-hops"))
                .as("if this ever starts binding, the docs may use the shorter name")
                .isNull();
    }

    @Test
    @DisplayName("the existing rate limit still binds, confirming the dash convention")
    void rateLimitConvention() {
        assertThat(bind("OMNISSA_API_RATE_LIMIT", "30", "omnissa.api.rate-limit"))
                .isEqualTo("30");
    }

    @Test
    @DisplayName("the OPTIONS-probe challenge binds from its documented name")
    void challengeOptions() {
        assertThat(bind("OMNISSA_API_CHALLENGE_OPTIONS", "false",
                "omnissa.api.challenge-options"))
                .as("a name that does not bind leaves the probe challenged while the operator "
                        + "believes they disabled it — or worse, the reverse")
                .isEqualTo("false");
    }

    @Test
    @DisplayName("the update-check settings bind from their documented names")
    void updateCheck() {
        assertThat(bind("OMNISSA_UPDATE_CHECK_ENABLED", "false", "omnissa.update.check-enabled"))
                .isEqualTo("false");
        assertThat(bind("OMNISSA_UPDATE_CHECK_INTERVAL", "PT6H", "omnissa.update.check-interval"))
                .isEqualTo("PT6H");
        assertThat(bind("OMNISSA_UPDATE_REGISTRY_REPO", "example/app", "omnissa.update.registry-repo"))
                .isEqualTo("example/app");
    }
}
