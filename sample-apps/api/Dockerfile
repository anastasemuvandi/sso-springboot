# syntax=docker/dockerfile:1
# GoRSSO sample API (minagri / minisante). Build from the REPO ROOT so the starter can be built too:
#
#   docker build -f sample-apps/api/Dockerfile -t gorsso/sample-api .
#   docker run --rm -p 8081:8080 -e SPRING_PROFILES_ACTIVE=minagri \
#     -e GORSSO_ISSUER_URI=http://localhost:8080/realms/government-internal \
#     -e GORSSO_JWK_SET_URI=http://host.docker.internal:8080/realms/government-internal/protocol/openid-connect/certs \
#     --add-host host.docker.internal:host-gateway gorsso/sample-api
#
# (sample-apps/api/Dockerfile.dockerignore keeps the context small under BuildKit.)

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 1) parent POM + starter, installed into the image's local repository
COPY pom.xml ./pom.xml
COPY sso-spring-boot-starter/pom.xml sso-spring-boot-starter/pom.xml
COPY sso-spring-boot-starter/src sso-spring-boot-starter/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -N install \
 && mvn -q -B -f sso-spring-boot-starter/pom.xml install -DskipTests -Djacoco.skip=true
# 2) the sample API itself
COPY sample-apps/api/pom.xml sample-apps/api/pom.xml
COPY sample-apps/api/src sample-apps/api/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f sample-apps/api/pom.xml package -DskipTests \
 && cp sample-apps/api/target/gorsso-sample-api.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
# Both profiles listen on $PORT inside the container; map it to 8081 / 8082 on the host.
ENV SPRING_PROFILES_ACTIVE=minagri \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
