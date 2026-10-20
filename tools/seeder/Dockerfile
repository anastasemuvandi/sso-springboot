# syntax=docker/dockerfile:1.7
# GoRSSO dev seeder (one-shot). DEV CONVENIENCE: Terraform is the production onboarding path.
# Build from the REPO ROOT (it bakes in configs/ so the image runs with no mounts):
#   docker build -f tools/seeder/Dockerfile -t gorsso/seeder:dev .
# tools/seeder/Dockerfile.dockerignore keeps the context to what is copied below.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY tools/seeder/pom.xml tools/seeder/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f tools/seeder/pom.xml dependency:go-offline
COPY tools/seeder/src tools/seeder/src
# The unit tests read the committed configs (../../configs from tools/seeder).
COPY configs/institutions.yaml configs/institutions.yaml
COPY configs/delegated-permissions/ configs/delegated-permissions/
COPY configs/generated/ configs/generated/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f tools/seeder/pom.xml package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1004 seeder && adduser -S -u 1004 -G seeder -h /app -s /sbin/nologin seeder
WORKDIR /app
COPY --from=build /src/tools/seeder/target/gorsso-seeder.jar /app/gorsso-seeder.jar
COPY --from=build /src/configs/ /app/configs/
USER seeder
ENV SEED_CONFIG_DIR=/app/configs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/gorsso-seeder.jar"]
