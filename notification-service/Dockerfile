# Build context: the repository root (the module's parent POM lives there):
#   docker build -f notification-service/Dockerfile -t gor-sso/notification-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
RUN mvn -q -B -N install && mvn -q -B -f notification-service/pom.xml dependency:go-offline
COPY notification-service/src notification-service/src
RUN mvn -q -B -f notification-service/pom.xml package -DskipTests \
 && cp notification-service/target/notification-service-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
 && apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER app
EXPOSE 8002
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fs http://localhost:8002/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
