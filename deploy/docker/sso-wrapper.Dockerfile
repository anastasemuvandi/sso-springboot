# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# sso-wrapper image. Lives in deploy/docker/ because the wrapper module had no Dockerfile when the
# compose stack was written; if sso-wrapper/Dockerfile appears, point deploy/docker-compose.apps.yml
# and .gitlab-ci.yml at it and delete this file.
#
#   docker build -f deploy/docker/sso-wrapper.Dockerfile -t gorsso/sso-wrapper:dev .   # repo ROOT
#
# The jar is /app/app.jar and the working directory /app holds configs/ (templates.json,
# institutions.yaml, generated/), so the relative defaults (configs/...) resolve, and the ops
# commands run in the container too:
#   docker compose ... exec wrapper java -jar /app/app.jar delegated-check
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY mfa-policy/ mfa-policy/
COPY sso-wrapper/pom.xml sso-wrapper/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -N install \
 && mvn -q -B -f mfa-policy/pom.xml install -DskipTests \
 && mvn -q -B -f sso-wrapper/pom.xml dependency:go-offline || true
COPY sso-wrapper/src sso-wrapper/src
COPY sso-wrapper/openapi.json sso-wrapper/openapi.json
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f sso-wrapper/pom.xml package -DskipTests \
 && cp "$(ls sso-wrapper/target/sso-wrapper-*.jar | grep -v original | head -n1)" /src/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
 && addgroup -S -g 1005 wrapper \
 && adduser -S -u 1005 -G wrapper -h /app -s /sbin/nologin wrapper
WORKDIR /app
COPY --from=build --chown=wrapper:wrapper /src/app.jar /app/app.jar
COPY --chown=wrapper:wrapper configs/institutions.yaml /app/configs/institutions.yaml
COPY --chown=wrapper:wrapper configs/delegated-permissions/ /app/configs/delegated-permissions/
COPY --chown=wrapper:wrapper configs/generated/ /app/configs/generated/
USER wrapper
ENV PORT=8000 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TZ=UTC
EXPOSE 8000
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fs http://localhost:8000/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
