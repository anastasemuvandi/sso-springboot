# syntax=docker/dockerfile:1
# GoRSSO sample SPA. Build from the REPO ROOT (the SPA depends on ../../sdk/react):
#
#   docker build -f sample-apps/web/Dockerfile --build-arg MODE=minagri   -t gorsso/sample-web:minagri .
#   docker build -f sample-apps/web/Dockerfile --build-arg MODE=minisante -t gorsso/sample-web:minisante .
#   docker run --rm -p 5173:8080 -e API_UPSTREAM=http://host.docker.internal:8081 \
#     --add-host host.docker.internal:host-gateway gorsso/sample-web:minagri
#
# VITE_* values are baked in at build time from .env.<MODE>; override any of them with
# --build-arg (e.g. --build-arg VITE_GORSSO_BASE_URL=https://sso.gov.rw).

FROM node:22-alpine AS sdk
WORKDIR /src/sdk/react
COPY sdk/react/package.json sdk/react/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY sdk/react/ ./
RUN npm run build

FROM node:22-alpine AS build
ARG MODE=minagri
ARG VITE_GORSSO_BASE_URL
ARG VITE_CLIENT_ID
ARG VITE_REDIRECT_URI
ARG VITE_POST_LOGOUT_REDIRECT_URI
# keep the same relative layout so "file:../../sdk/react" resolves
COPY --from=sdk /src/sdk/react /src/sdk/react
WORKDIR /src/sample-apps/web
COPY sample-apps/web/package.json sample-apps/web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY sample-apps/web/ ./
# Only set the overrides that were passed (an empty ARG must not blank the .env value).
RUN set -e; \
    for v in VITE_GORSSO_BASE_URL VITE_CLIENT_ID VITE_REDIRECT_URI VITE_POST_LOGOUT_REDIRECT_URI; do \
      val="$(printenv "$v" || true)"; [ -n "$val" ] && echo "$v=$val" >> ".env.${MODE}.local"; \
    done; \
    npx tsc --noEmit && npx vite build --mode "${MODE}" && mv "dist/${MODE}" /out

FROM nginx:alpine
ENV API_UPSTREAM=http://sample-api:8080
COPY sample-apps/web/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /out /usr/share/nginx/html
EXPOSE 8080
