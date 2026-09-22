# L3: multi-stage build so the runtime image never carries the JDK's compiler/build tools or the .m2 cache.
# Build stage: the project's own Maven Wrapper, not a bare `maven` image - the wrapper pins the exact Maven
# version this project builds with everywhere else (IDE, CI-to-be, developer machines), so the container build
# uses the identical toolchain rather than whatever a generic maven:* tag happens to ship.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies first, cached separately from source changes - the common Docker-layer-caching trick, so an
# edit to a .java file does not re-download the whole dependency tree on every image rebuild.
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests

# Runtime stage: JRE only, non-root user (a container running as root is a needless privilege for a plain
# Spring Boot jar with no reason to touch anything outside its own process). curl is added only so
# docker-compose.yml's healthcheck has something to call - not otherwise needed by the app.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --shell /usr/sbin/nologin blockevidence
WORKDIR /app
COPY --from=build /build/target/backend-*.jar app.jar
RUN chown blockevidence:blockevidence app.jar
USER blockevidence
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
