# IPPIS mock (dev/test only). Build from the REPO ROOT so the parent POM is in context:
#   docker build -f ippis-mock/Dockerfile -t gorsso/ippis-mock .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./pom.xml
COPY ippis-mock/pom.xml ippis-mock/pom.xml
RUN mvn -q -B -N install && mvn -q -B -f ippis-mock/pom.xml dependency:go-offline
COPY ippis-mock/src ippis-mock/src
RUN mvn -q -B -f ippis-mock/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
 && addgroup -S -g 1002 ippis \
 && adduser -S -u 1002 -G ippis -h /ippis -s /sbin/nologin ippis \
 && mkdir -p /ippis/data && chown -R ippis:ippis /ippis
WORKDIR /ippis
COPY --from=build --chown=ippis:ippis /src/ippis-mock/target/ippis-mock.jar app.jar
# Optional JSON persistence (IPPIS_MOCK_DATA_FILE); unset = in-memory, reseeded on start.
VOLUME ["/ippis/data"]
USER ippis
ENV PORT=8002 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8002
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fs http://localhost:8002/health || exit 1
ENTRYPOINT ["java", "-jar", "/ippis/app.jar"]
