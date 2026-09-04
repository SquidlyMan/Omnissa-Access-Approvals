package com.omnissa.access.approval.update;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The version this build is, from one place.
 *
 * <p>Prefers the Maven build-info the packaging plugin writes, which is also
 * present when running from an IDE or {@code spring-boot:run}. Falls back to
 * the jar manifest, which is only there inside a packaged jar — the reason the
 * dashboard used to say {@code dev} in local development.
 */
@Component
public class AppVersion {

    private final ObjectProvider<BuildProperties> buildProperties;

    public AppVersion(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    /** The running version as a string, or {@code dev} when nothing supplies one. */
    public String current() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null && build.getVersion() != null && !build.getVersion().isBlank()) {
            return build.getVersion();
        }
        String manifest = AppVersion.class.getPackage().getImplementationVersion();
        return manifest != null && !manifest.isBlank() ? manifest : "dev";
    }

    /** The running version parsed, or empty when it is not a release build. */
    public Optional<Semver> semver() {
        return Semver.parse(current());
    }
}
