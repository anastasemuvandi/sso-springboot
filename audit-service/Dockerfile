# syntax=docker/dockerfile:1.7
# Build from the repository root (the module inherits ../pom.xml):
#   docker build -f audit-service/Dockerfile -t gorsso/audit-service .
# audit-service/Dockerfile.dockerignore keeps the context small.

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY audit-service/pom.xml audit-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -N install \
 && mvn -q -B -f audit-service/pom.xml dependency:go-offline
COPY audit-service/src audit-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -f audit-service/pom.xml package -DskipTests \
 && java -Djarmode=tools -jar audit-service/target/audit-service.jar extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 1001 audit \
 && useradd --system --uid 1001 --gid audit --home-dir /app --shell /usr/sbin/nologin audit
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER audit

ENV PORT=8001 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    TZ=UTC
EXPOSE 8001

# Readiness includes the database. Flyway migrates on start unless RUN_MIGRATIONS=false (e.g. a
# worker-only replica: AUDIT_WORKERS_ENABLED=true, behind one that migrates).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fs http://localhost:8001/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
