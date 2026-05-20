# Stage 1 — build the JAR
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw package -DskipTests -q

# Stage 2 — minimal runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/omnissa-approval-*.jar app.jar

# Config, keystore, and database live on mounted volumes
VOLUME ["/app/config", "/app/data"]

# 8081 for Caddy mode (HTTP internal), 8443+8080 for standalone SSL mode
EXPOSE 8081 8443 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar", \
  "--spring.config.additional-location=file:./config/"]
