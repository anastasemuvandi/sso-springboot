# Console BFF + the built React console, one image on :3001.
# Build from the REPO ROOT (needs the parent POM and console-web/):
#   docker build -f console-bff/Dockerfile -t gorsso/console-bff .
# Keep node_modules/ and target/ out of the context with a root .dockerignore.

FROM node:22-alpine AS web
WORKDIR /web
COPY console-web/package.json console-web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY console-web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY console-bff/pom.xml console-bff/pom.xml
RUN mvn -q -B -N install && mvn -q -B -f console-bff/pom.xml dependency:go-offline
COPY console-bff/src console-bff/src
RUN mvn -q -B -f console-bff/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
 && addgroup -S -g 1003 bff \
 && adduser -S -u 1003 -G bff -h /app -s /sbin/nologin bff
WORKDIR /app
COPY --from=build --chown=bff:bff /src/console-bff/target/console-bff.jar app.jar
COPY --from=web --chown=bff:bff /web/dist /app/static
USER bff
# Secrets: ADMIN_UI_CLIENT_SECRET_FILE / REDIS_PASSWORD_FILE (Docker/K8s secrets) are supported.
ENV PORT=3001 \
    CONSOLE_WEB_DIST=/app/static \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 3001
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fs http://localhost:3001/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
