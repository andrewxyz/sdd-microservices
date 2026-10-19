# One Dockerfile for every Spring module; the caller picks which with --build-arg MODULE=<name>.
# Build context is backend/, so the parent POM and the shared libraries are all in scope.
# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /build

COPY pom.xml ./
COPY shared-events/pom.xml shared-events/
COPY platform-common/pom.xml platform-common/
COPY discovery-server/pom.xml discovery-server/
COPY config-server/pom.xml config-server/
COPY echo-service/pom.xml echo-service/
COPY api-gateway/pom.xml api-gateway/
COPY infra-tests/pom.xml infra-tests/
COPY payment-service/pom.xml payment-service/
COPY enrollment-service/pom.xml enrollment-service/
COPY certificate-service/pom.xml certificate-service/
COPY notification-service/pom.xml notification-service/

# Warm the dependency cache in its own layer, so editing source does not re-download the world.
# The cache mount is shared across module builds, which is what keeps the cold build inside the
# 10 minutes SC-001 allows.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl "${MODULE}" -am dependency:go-offline -DskipTests || true

COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl "${MODULE}" -am package -DskipTests \
 && cp "${MODULE}/target/${MODULE}-"*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
# wget comes from busybox in this image and is what the Compose health checks use.
RUN addgroup -S learnhub && adduser -S learnhub -G learnhub
WORKDIR /app
COPY --from=build --chown=learnhub:learnhub /build/app.jar /app/app.jar
USER learnhub
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
