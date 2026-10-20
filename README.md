# GoRSSO / SSO.RISA (Spring Boot + React)

The Government of Rwanda single sign-on and identity-management platform: **Keycloak 26.2**
authenticates people; a **Spring Boot wrapper** in front of it exposes typed, RBAC-gated REST
endpoints, drives Keycloak through the Admin API and runs the gates (per-client access, join key,
MFA) that can refuse a token Keycloak has already minted. A React console (behind a Spring Boot
BFF) is the only admin UI for day-to-day work.

This repository is the port of the Python/Next.js reference implementation. The specification is
[`docs/SPEC.md`](docs/SPEC.md); team conventions are in
[`docs/BUILD_CONVENTIONS.md`](docs/BUILD_CONVENTIONS.md); each built feature has a note in
[`docs/implemented/`](docs/implemented/).

## Layout

| Path | What |
|---|---|
| `sso-wrapper/` | The wrapper API (port 8000). Also the ops CLI: `java -jar sso-wrapper/target/sso-wrapper-*.jar <command>` |
| `console-bff/` + `console-web/` | BFF (port 3001, token relay, Redis sessions) and the React 18 console it serves |
| `audit-service/` | Hash-chained, append-only audit log (port 8001) |
| `notification-service/` | Detector over the audit trail + email/in-app/webhook channels (port 8002) |
| `ippis-mock/` | Simulated IPPIS (GESB) HR feed for dev and tests (8002 in the container, 8003 on the host) |
| `mfa-policy/`, `keycloak-extensions/` | Shared TOTP rule; the `gorsso-mfa` Keycloak SPI (authenticators + audit listener) |
| `keycloak-theme/` | Keycloakify login/email theme (`government-citizens`, `gor-admin`) |
| `keycloak/Dockerfile` | Keycloak 26.2 image: providers + theme + realms, `kc.sh build` |
| `sso-spring-boot-starter/`, `sdk/`, `sample-apps/` | Integration starter, generated SDKs + React SDK, demo apps |
| `configs/` | `institutions.yaml` (which institutions exist), `delegated-permissions/templates.json` (what local admins may do), `realms/*.json`, `generated/fgap-v2-plan.json` |
| `deploy/` | Docker Compose (base, apps, MFA, staging, prod overlays), Postgres init, `.env.example`, the wrapper Dockerfile |
| `tools/seeder/` | Idempotent dev seeder (standalone Java; not part of the root build) |
| `infra/terraform/` | Onboarding as code (production path) |
| `ci/`, `.gitlab-ci.yml` | CI pipeline and the isolation-report gate |
| `docs/runbooks/` | Onboarding, MFA, IPPIS sync, identity DB, database access, production sizing, TLS |

## Quick start

Requires Docker (with Compose v2.20+), and for building/testing outside Docker: JDK 21+ and Maven.
Node is not needed on the PATH: the repo carries `.tools/node` for the frontend targets.

```bash
make start      # build images, start the stack; the one-shot `seed` service seeds on first up
make seed       # re-run the seeder at any time (idempotent)
make ps         # status;  make logs S=wrapper
make stop       # stop;    make clean  (also deletes every volume)
```

`make start` is `docker compose -p gorsso -f deploy/docker-compose.base.yml -f
deploy/docker-compose.apps.yml up -d --build`. Copy `deploy/.env.example` to `deploy/.env` to change
ports or secrets (the Makefile passes it when it exists). Every host port is configurable
(`HOST_PORT_*`), so this stack can run next to another one.

The seeder (`tools/seeder`) gives you, in realm `government-internal`: the institutions `minagri`
and `minisante` (groups, departments, `inst:<slug>:*` roles rendered from the templates,
`<slug>-<app>` clients with the `institution` claim mapper and an `sso-access-groups` policy, an
Organization each), the shared control client `dp-shared-portal`, public PKCE clients for the sample
SPAs, the control-plane clients `sso-client` and `sso-admin-ui`, the FGAP v2 permissions from
`configs/generated/fgap-v2-plan.json`, and the demo people below. Production onboarding is
Terraform, not the seeder: see [`docs/runbooks/onboard-institution.md`](docs/runbooks/onboard-institution.md).

### URLs (dev defaults)

| Service | URL |
|---|---|
| Console | http://localhost:3001 |
| Wrapper | http://localhost:8000 (Swagger UI `/docs/api`, health `/health/`) |
| Keycloak | http://localhost:8080 (admin console `/admin`; health on the internal port 9000) |
| Audit service | http://localhost:8001 (`X-Audit-API-Key`) |
| Notification service | http://localhost:8002 |
| IPPIS mock | http://localhost:8003 (`X-IPPIS-API-Key`) |
| MailHog | http://localhost:8025 |
| Adminer | http://localhost:8081 (server `postgres`) |
| Postgres / Redis | localhost:5435 / localhost:6380 (loopback only) |

### Dev credentials (never use outside a local stack)

| Who | Username | Password |
|---|---|---|
| Keycloak bootstrap admin (master realm) | `admin` | `admin` |
| Platform admin (`super_admin`) | `superadmin` | `GorSsoDev#2026` |
| Institution admins (`inst:<slug>:admin`) | `minagri.admin`, `minisante.admin` | `GorSsoDev#2026` |
| Other demo people per institution | `<slug>.hr`, `<slug>.auditor`, `<slug>.staff`, `<slug>.planner` (department member), `<slug>.unassigned` (not a group member) | `GorSsoDev#2026` |
| Postgres superuser | `gorsso` | `gorsso-dev` |
| Postgres service roles | `keycloak`, `sso_identity_rw`, `audit`, `notification` | `keycloak-dev`, `sso-identity-dev`, `audit-dev`, `notification-dev` |

Client secrets: `sso-client` = `gorsso-dev-sso-client`, `sso-admin-ui` = `gorsso-dev-admin-ui`.
API keys: audit `gorsso-dev-audit-key`, IPPIS `gorsso-dev-ippis-key`, notification
`gorsso-dev-notification-key`. Sample SPA clients (public, PKCE S256): `minagri-kubaka-spa`
(`http://localhost:5173/callback`) and `minisante-hmis-spa` (`http://localhost:5174/callback`).
The realm JSONs also import the reference's legacy demo users and clients (for example
`kwizera_landowner` / `GovRwanda@2025`).

## Building and testing

```bash
make build            # every Maven module (tests skipped) + the seeder + console-web
make test             # unit tests: Maven modules, seeder, console-web, keycloak-theme, sdk/react
make it               # adds the Testcontainers suites (Keycloak 26.2, Postgres, Redis)
mvn -B -Pcoverage verify   # what CI runs: JaCoCo line coverage >= 60% per module
make openapi-check    # committed openapi.json of wrapper, audit, notification are current
make tf-validate      # Terraform fmt + validate, offline (uses docker if terraform is absent)
make smoke            # against a running stack: API end-to-end checks + a real browser login
```

One module: `mvn -B -f <module>/pom.xml verify` after `mvn -B -N install` (and
`mvn -B -f mfa-policy/pom.xml install -DskipTests` for modules that use it).

Config and contract gates (`make registry-validate`, `delegated-validate`, `fgap-plan-check`,
`delegated-check`, `verify`) run wrapper ops commands; against the local stack, first
`set -a; source deploy/dev-cli.env; set +a`. The `Makefile` header lists every subcommand.

CI is [`.gitlab-ci.yml`](.gitlab-ci.yml); its stages and required variables are documented in its
header, and infrastructure in [`docs/implemented/infrastructure.md`](docs/implemented/infrastructure.md).
