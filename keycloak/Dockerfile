# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# GoRSSO Keycloak 26.2 image: gorsso-mfa providers + Keycloakify login theme +
# realm definitions, built for `start --optimized`.
#
#   docker build -f keycloak/Dockerfile -t gorsso/keycloak:dev .      # repo ROOT as context
#   docker build -f keycloak/Dockerfile --build-arg BUILD_THEME=false .   # skip the theme
#
# The build context is the repository root (it needs pom.xml, mfa-policy/,
# keycloak-extensions/, keycloak-theme/ and configs/realms/).
# keycloak/Dockerfile.dockerignore keeps node_modules/target/.git out of it.
#
# BUILD OPTIONS VS RUNTIME OPTIONS. `kc.sh build` freezes a set of options into
# the image, and `start --optimized` then REFUSES to run if any of them is
# supplied again as an environment variable ("Build time option ... not usable
# with pre-built image and --optimized"):
#
#   Build time (here, and NOT in a compose overlay):
#     KC_DB, KC_FEATURES, KC_HEALTH_ENABLED, KC_METRICS_ENABLED
#   Run time (in the compose overlay):
#     KC_DB_URL_HOST, KC_DB_USERNAME, KC_DB_PASSWORD, KC_HOSTNAME (full URL),
#     KC_HTTP_ENABLED, KC_PROXY_HEADERS=xforwarded, KC_BOOTSTRAP_ADMIN_*,
#     JAVA_OPTS_KC_HEAP, KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_URL / _API_KEY
#
# Health and metrics are served on the management port 9000
# (/health/ready, /health/live, /metrics), not on 8080.
# ---------------------------------------------------------------------------

ARG KC_VERSION=26.2
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG NODE_IMAGE=node:22-alpine

# ──────────────────────── 1. gorsso-mfa provider (Maven) ────────────────────────
# Builds mfa-policy (the shared TOTP rule) and keycloak-extensions, which shades it
# into target/gorsso-mfa.jar. Unit tests run here too unless SKIP_TESTS=true.
FROM ${MAVEN_IMAGE} AS providers
ARG SKIP_TESTS=false
WORKDIR /build
COPY pom.xml ./
COPY mfa-policy/ mfa-policy/
COPY keycloak-extensions/ keycloak-extensions/
# The parent POM lists every module; build only the two this image needs, with
# their own POMs (the parent resolves through ../pom.xml once installed).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -N install \
 && mvn -B -q -f mfa-policy/pom.xml install -DskipTests=${SKIP_TESTS} \
 && mvn -B -q -f keycloak-extensions/pom.xml package -DskipTests=${SKIP_TESTS} \
 && mkdir -p /out \
 && cp keycloak-extensions/target/gorsso-mfa.jar /out/

# ──────────────────────── 2. Keycloakify theme (Node) ────────────────────────
# `npm ci && npm run build-keycloak-theme` in keycloak-theme/ produces
# dist_keycloak/*.jar. Keycloakify packages that JAR with Maven, hence maven+JDK.
# Skippable: BUILD_THEME=false, or no keycloak-theme/package.json in the context.
# Keycloakify's legacy-range JARs (e.g. *-for-kc-22-to-25.jar) are not for 26 and are dropped.
FROM ${NODE_IMAGE} AS theme
ARG BUILD_THEME=true
WORKDIR /src
# `keycloak-them[e]` is a glob, so a missing directory is not an error; the
# Dockerfile itself rides along so the COPY always has at least one source.
COPY keycloak/Dockerfile keycloak-them[e] /src/
RUN --mount=type=cache,target=/root/.npm \
    --mount=type=cache,target=/root/.m2 \
    set -eu; mkdir -p /out; \
    if [ "${BUILD_THEME}" != "true" ]; then echo "theme: skipped (BUILD_THEME=${BUILD_THEME})"; exit 0; fi; \
    if [ ! -f package.json ]; then echo "theme: keycloak-theme/ not in the build context; skipped"; exit 0; fi; \
    apk add --no-cache maven openjdk21-jdk >/dev/null; \
    npm ci --no-audit --no-fund; \
    npm run build-keycloak-theme; \
    for jar in dist_keycloak/*.jar; do \
      [ -e "$jar" ] || continue; \
      case "$jar" in \
        *-to-2[0-5].jar) echo "theme: ignoring $jar (not for Keycloak 26)";; \
        *) cp "$jar" /out/; echo "theme: $jar";; \
      esac; \
    done; \
    ls /out/*.jar >/dev/null 2>&1 || { echo "theme: npm run build-keycloak-theme produced no JAR" >&2; exit 1; }

# ──────────────────────── 3. Keycloak build (kc.sh build) ────────────────────────
FROM quay.io/keycloak/keycloak:${KC_VERSION} AS builder

# `organization` is SINGULAR — the plural makes the container refuse to boot
# ("unrecognized feature"). FGAP v2 is the versioned form of
# admin-fine-grained-authz. `recovery-codes` is a PREVIEW feature in 26.2.5 and
# the browser-totp flow needs it (docs/implemented/mfa-keycloak.md).
ARG KC_FEATURES=organization,admin-fine-grained-authz:v2,recovery-codes

ENV KC_DB=postgres \
    KC_FEATURES=${KC_FEATURES} \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

# Providers and theme BEFORE `kc.sh build`: the build is what registers a
# provider in an optimized image; a JAR added after it is not seen.
COPY --from=providers --chown=keycloak:keycloak /out/ /opt/keycloak/providers/
COPY --from=theme     --chown=keycloak:keycloak /out/ /opt/keycloak/providers/
# Version-controlled realm JSONs, imported once on first boot (--import-realm).
COPY --chown=keycloak:keycloak configs/realms/*.json /opt/keycloak/data/import/

RUN /opt/keycloak/bin/kc.sh build && /opt/keycloak/bin/kc.sh show-config | grep -E "kc\.(db|features|health|metrics)" || true

# ──────────────────────── 4. Runtime ────────────────────────
FROM quay.io/keycloak/keycloak:${KC_VERSION}

COPY --from=builder /opt/keycloak/ /opt/keycloak/

EXPOSE 8080 8443 9000

# The image has no curl; bash's /dev/tcp is enough for a readiness probe on 9000.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\": *\"UP\"' <&3"]

# `--import-realm` reads the realm JSON baked in above and imports it once on
# first boot (skipped if the realm already exists in Postgres). KC_HOSTNAME,
# KC_DB_URL_HOST, secrets etc. are injected by the per-environment compose file.
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--optimized", "--import-realm"]
