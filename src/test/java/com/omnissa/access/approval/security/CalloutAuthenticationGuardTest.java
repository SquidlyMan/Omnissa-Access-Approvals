package com.omnissa.access.approval.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The callout endpoint must not accept anonymous requests by accident (#70).
 *
 * <p>{@code POST /api/approvals/new} has to be reachable from the internet
 * because the Omnissa Access cloud does the POSTing. Basic authentication on it
 * was supported but blank by default, so the shipped configuration accepted
 * approval requests from anyone who found the URL, and an injected request is
 * indistinguishable from a real one in the queue — approving it grants real
 * entitlements.
 *
 * <p>The point of these tests is that the insecure state is not reachable
 * silently. Either credentials exist, or somebody has said in writing that
 * anonymous ingest is intended.
 */
class CalloutAuthenticationGuardTest {

    /** A tenant is configured, which is when anonymous ingest starts to matter. */
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CalloutAuthenticationGuard.class)
            .withPropertyValues("omnissa.bootstrap.url=tenant.example.com");

    @Test
    @DisplayName("no credentials and no declaration: the application refuses to start")
    void refusesToStartWhenIngestWouldBeAnonymous() {
        context.withPropertyValues("omnissa.api.username=")
                .run(started -> {
                    assertThat(started)
                            .as("starting here would leave an open ingest path that nobody chose")
                            .hasFailed();
                    assertThat(started.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    // The root cause carries the guidance; the bean-creation
                    // wrapper around it does not.
                    assertThat(started.getStartupFailure())
                            .as("the message has to name both ways out, or it just blocks "
                                    + "a deployment without saying what to do")
                            .rootCause()
                            .hasMessageContaining("OMNISSA_API_USERNAME")
                            .hasMessageContaining("OMNISSA_API_ALLOW_UNAUTHENTICATED");
                });
    }

    @Test
    @DisplayName("credentials configured: starts")
    void startsWhenCredentialsAreSet() {
        context.withPropertyValues("omnissa.api.username=access", "omnissa.api.password=secret")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    @DisplayName("anonymous ingest explicitly declared: starts, and says so")
    void startsWhenAnonymousIngestIsDeclared() {
        context.withPropertyValues("omnissa.api.username=",
                        "omnissa.api.allow-unauthenticated=true")
                .run(started -> assertThat(started)
                        .as("an explicit declaration is a decision, not an accident")
                        .hasNotFailed());
    }

    @Test
    @DisplayName("a blank-but-present username is still anonymous")
    void whitespaceIsNotACredential() {
        context.withPropertyValues("omnissa.api.username=   ")
                .run(started -> assertThat(started)
                        .as("whitespace satisfies a null check without authenticating anyone")
                        .hasFailed());
    }

    @Test
    @DisplayName("before a tenant is configured, no credential is demanded")
    void firstRunStillNeedsNothing() {
        // #67 made the tool start with nothing configured, so that a new operator
        // sees a sign-in page rather than a stack trace. With no tenant there is
        // nothing an injected request could reach, so requiring a credential here
        // would cost that for no security.
        new ApplicationContextRunner()
                .withUserConfiguration(CalloutAuthenticationGuard.class)
                .withPropertyValues("omnissa.api.username=", "omnissa.bootstrap.url=")
                .run(started -> assertThat(started)
                        .as("an unconfigured install must still come up unaided")
                        .hasNotFailed());
    }
}
