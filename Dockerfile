# Stage 1 — build the JAR
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw package -DskipTests -q

# Stage 2 — minimal runtime image
FROM eclipse-temurin:25-jre
# temurin dropped curl after 21-jre; the container healthcheck depends on it.
# The Ubuntu base also ships pebble, Canonical's service manager, as a loose
# Go binary that nothing here runs; it is removed so a Go advisory can never
# be reported against a Java image (the 1.23.4 image carried thirteen).
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && rm -f /usr/bin/pebble
WORKDIR /app
COPY --from=build /app/target/omnissa-approval-*.jar app.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

# The application runs as an unprivileged user (since 1.24.0), so a flaw in it
# does not hand over root inside the container. The uid is fixed and high so
# that it is the same in every build and collides with nothing on a host: the
# updater and deploy.sh chown the bind-mounted directories to it by number.
# The directories are created and handed over BEFORE the VOLUME line, because
# a new named volume is initialised with the ownership the image has there.
RUN groupadd --system --gid 10001 approvals \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin approvals \
    && mkdir -p /app/config /app/data /app/control \
    && chown 10001:10001 /app/data /app/control \
    && chmod 0755 /app/entrypoint.sh

# Config, keystore, and database live on mounted volumes. /app/control is
# where an approved update is written for the host-side updater — kept apart
# from /app/data so a restored backup can never carry a pending deploy.
VOLUME ["/app/config", "/app/data", "/app/control"]

# 8081 for Caddy mode (HTTP internal), 8443+8080 for standalone SSL mode.
# All above 1024, so none needs a privilege to bind.
EXPOSE 8081 8443 8080

USER 10001:10001

# entrypoint.sh checks the mounts are writable by this user, prints the fix if
# they are not, and then exec's java — so java is still PID 1 and still gets
# SIGTERM directly.
ENTRYPOINT ["/app/entrypoint.sh"]
