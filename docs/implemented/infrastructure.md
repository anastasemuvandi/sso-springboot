# Infrastructure: compose stack, seeder, realms, Terraform, Makefile, CI (spec §3, §5.17, §8, §9)

## Code map

| Path | What |
|---|---|
| `deploy/docker-compose.base.yml` | Postgres 16 + Keycloak 26.2 built from `keycloak/Dockerfile`, run as `start-dev --import-realm` with `configs/realms` bind-mounted |
| `deploy/smoke/` | `api-smoke.sh` + `console-login.mjs` (`make smoke`) |
| `deploy/docker-compose.apps.yml` | The dev wrapper runs Spring profile `seed-mirror` (fills `sso_identity` with the dev roster; off in staging/prod). redis, seed (one-shot), wrapper, console-bff, audit-service, notification-service, ippis-mock, mailhog, adminer; healthchecks on every service (Keycloak on `:9000/health/ready`) |
| `deploy/docker-compose.mfa.yml` | Overlay: one-shot `mfa-install` (wrapper ops command) before the wrapper starts; theme caches off |
| `deploy/docker-compose.staging.yml`, `deploy/docker-compose.prod.yml` | Overlays: `environment: !override` on Keycloak (back to the image's `start --optimized --import-realm`, no build options), `entrypoint/command: !reset`, `${VAR:?}` on every secret, `APP_ENV=staging/prod`, seeder behind profile `seed` (control plane only), MailHog/Adminer disabled. Prod adds https-portal (TLS), `KC_PROXY_HEADERS=xforwarded` + `KC_PROXY_TRUSTED_ADDRESSES`, `COOKIE_SECURE=true`, memory limits, rotated logs, real IPPIS required (`IPPIS_SERVER_URL`; the mock only with `--profile mock`) |
| `deploy/.env.example` | Every variable the compose files read (ports, public URLs, DB passwords, secrets, seeder, staging/prod hostnames) |
| `deploy/dev-cli.env` | Env for running the wrapper jar as an ops CLI on the host against the dev stack |
| `deploy/postgres/initdb/10-databases.sh` | First-init only: `keycloak`, `sso_identity`, `audit`, `notifications`, each owned by its own role, `CONNECT` revoked from `PUBLIC` (verified: `sso_identity_rw` cannot open `keycloak`). `<NAME>_FILE` supported |
| `deploy/docker/sso-wrapper.Dockerfile` | Wrapper image (the module has no Dockerfile of its own yet): jar at `/app/app.jar`, `configs/` baked into `/app/configs` |
| `tools/seeder/` | Standalone Java 21 seeder (Jackson + JDK HttpClient; not a root-pom module), `Dockerfile`, unit tests against the real `configs/` |
| `configs/realms/*.json` | Realm fixes (below) |
| `infra/terraform/` | Reviewed against §5.17 (below) |
| `Makefile` | Developer commands; header lists the wrapper ops subcommands the targets call |
| `.gitlab-ci.yml`, `ci/check-isolation-reports.sh`, `ci/isolation-required.txt` | Pipeline; isolation gate that fails when a required scenario class is missing or ran 0 tests |
| `pom.xml` profile `coverage` | `jacoco:check`, LINE COVEREDRATIO >= 0.60 per module, `mvn -Pcoverage verify` |
| `docs/runbooks/` | onboard-institution, enable-mfa-totp, ippis-sync, create-sso-identity-db, local-database-access, production-sizing (3 VMs), tls |

## Ports (host, each `HOST_PORT_*` overridable)

Keycloak 8080, wrapper 8000, console 3001, audit 8001, notification 8002, ippis-mock 8003
(container 8002), Postgres 5435 and Redis 6380 (loopback only via `HOST_BIND`), MailHog 8025,
Adminer 8081 (loopback). Keycloak management (9000) is not published.

## Keycloak settings (spec §3 gotchas)

`KC_HOSTNAME` is the full browser URL (`PUBLIC_KEYCLOAK_URL`, default
`http://localhost:${HOST_PORT_KEYCLOAK}`) with `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, so tokens carry
one issuer while the wrapper/seeder talk to `http://keycloak:8080`. `KC_PROXY_HEADERS=xforwarded`,
`KC_BOOTSTRAP_ADMIN_*`, `JAVA_OPTS_KC_HEAP` plus `mem_limit`. The dev service sets the build options
(`KC_DB`, `KC_FEATURES=organization,admin-fine-grained-authz:v2,recovery-codes`,
`KC_HEALTH_ENABLED`, `KC_METRICS_ENABLED`) because `start-dev` re-augments; the overlays drop them
with `!override`.

## Seeder (`tools/seeder`, the compose `seed` service, `make seed`)

Dev convenience; Terraform is the production path. Idempotent (each step reads first, converges
what differs). Steps against realm `government-internal`:

1. Realm switches: `organizationsEnabled`, `adminPermissionsEnabled` (materialises
   `admin-permissions`), user profile `unmanagedAttributePolicy=ENABLED` (else `institution` is
   dropped silently), the identity-first `Organization` browser step disabled (single-page login),
   realm SMTP to MailHog (`SEED_REALM_SMTP_HOST`), the `gorsso-audit` event listener added
   (`SEED_AUDIT_LISTENER`, so Keycloak's own events reach the audit service).
2. Platform roles `super_admin`, `admin`, `system_admin`, `employee`.
3. Per institution (`SEED_INSTITUTIONS`, default `minagri,minisante`): group `/<slug>` (attribute
   `institution`), departments from the registry (else `planning`) read via `/groups/{id}/children`,
   `inst:<slug>:*` roles rendered from `templates.json` (grant role + `provision.roles`, as
   `PermissionTemplate.rolesToProvision`), clients `<slug>-<app>` from `institutions.yaml`
   (confidential, authorization services on, `sso.client.owner_institution`, client roles,
   `institution` claim mapper, `sso-access-groups` group policy admitting `/<slug>`), an
   Organization, and the people `<slug>.admin|hr|auditor` (grant roles), `<slug>.staff`,
   `<slug>.planner` (department member), `<slug>.unassigned` (attribute, no membership).
4. `dp-shared-portal`: a client owned by no institution (DP-06 control).
5. Sample SPA public PKCE clients (`SEED_SAMPLE_SPAS`, default `minagri-kubaka-spa` on
   `http://localhost:5173`, `minisante-hmis-spa` on `http://localhost:5174`; owner from the
   prefix; no authz policy because public clients cannot carry one).
6. Control plane: `sso-client` (fixed secret, service account with the wrapper's
   `REQUIRED_REALM_MANAGEMENT_ROLES`, `institution` mapper) and `sso-admin-ui` (confidential, PKCE
   S256, redirect `<console>/login/oauth2/code/sso-admin-ui`, post-logout `<console>/`, for every
   URL in `CONSOLE_PUBLIC_URLS`).
7. FGAP v2: the policies and permissions of `configs/generated/fgap-v2-plan.json` for the seeded
   institutions (and `_shared` entries), resources resolved to ids, `*` = all of a type,
   converged when resources/policies differ. A permission whose resources do not resolve is
   skipped (an empty list would mean "every object").
8. `superadmin` with realm role `super_admin`.

Demo users are refused when `APP_ENV` is staging/prod. The demo password must satisfy the realm
policy (checked before anything is written).

## Realm JSON fixes (`configs/realms/`)

All three: only `loginTheme` set (`government-citizens`; `gor-admin` on `government-admin`),
`internationalizationEnabled: true`, `supportedLocales: ["en","rw"]`, `defaultLocale: "en"`,
password policy `length(10) and upperCase(1) and lowerCase(1) and digits(1)`, brute force
`failureFactor 5`, `permanentLockout false`, `maxTemporaryLockouts 0`, `waitIncrementSeconds =
maxFailureWaitSeconds = minimumQuickLoginWaitSeconds = 900`, `quickLoginCheckMilliSeconds 0`. The
last one matters: measured on 26.2.5, with the quick-login check at 1000 ms two failures inside a
second already locked the account (for `minimumQuickLoginWaitSeconds`); with 0, failures 1–4 do
not lock and the 5th locks for exactly 900 s, and 10 failures still give 900 s. The citizens test
user's `password123` became `Citizen#Dev2026` to satisfy the policy. All three import cleanly on
`quay.io/keycloak/keycloak:26.2` (26.2.5) and on the built image.

## Terraform review (§5.17)

Already read the YAML/JSON and the committed plan directly (`yamldecode`/`jsondecode`/`file`): no
`external` data source, no `local-exec`. Changes: locales `["en","rw"]`; brute force made flat
(quick-login check 0, min wait 900); clients get `sso.client.owner_institution` (spec §5.5) besides
`institution`; Python references in comments/README now name the Java ports;
`scripts/import-existing.sh` reads the registry with `terraform console` (no python3).
`ignore_changes` on `mfa_totp_mode` kept. `terraform fmt -check -recursive` and `init
-backend=false` + `validate` pass with `hashicorp/terraform:1.9.8`.

## Tests and verification

- `mvn -B -f tools/seeder/pom.xml package` runs `SourcesTest` (role rendering, registry apps,
  people per tier, FGAP plan filtering, password policy check, description trimming).
- Compose configs (`docker compose config`) validate for dev, dev+mfa, dev+staging, dev+prod; a
  missing secret in staging/prod fails interpolation.
- `make smoke` (against a running, seeded stack; `HOST_PORT_*` as for compose):
  `deploy/smoke/api-smoke.sh` (health, discovery, `/auth/login` gates, audit trail, delegated admin,
  scoped directory, ops commands in the wrapper container, brute-force -> notification alert) then
  `deploy/smoke/console-login.mjs` (headless Chromium via console-web's Playwright: real login
  through console-bff -> wrapper -> Keycloak; screenshots in `deploy/smoke/out/`).
- Full live run (2026-10-20, project `gorsso`, ports 18080/18000/13001/18001/18002/18003/15435/
  16380/18025/18081), all 11 services healthy (seed exited 0):
  - PASS: wrapper `/health/ready`; discovery (issuer = public Keycloak URL); `POST /auth/login`
    `minagri.staff` -> `minagri-kubaka-web` 200 and -> `minisante-hmis` 403 `access_denied`;
    audit-service holds `auth.login.success` and `authz.access.denied` for those calls;
    `/institution-admin/me` as `minagri.admin` -> `minagri`; `/employees` 22 rows, all minagri
    (mirror filled by the dev `seed-mirror` profile); 6 failed logins -> `brute_force` CRITICAL
    alert within one detector cycle; browser: `superadmin` lands on `/dashboard` (overview 200),
    `minagri.admin` lands on `/institution` (overview 200) and the access matrix loads (200).
  - Also checked: Postgres databases/owners and `CONNECT` isolation; seeder 101 changes then 0;
    Keycloak-direct FGAP as `minagri.admin`: `/minagri` members 200, `/minisante` 403.
  - FAIL (wrapper, not infrastructure): every ops command in the container
    (`delegated-check`, `fgap-plan-check`, `verify --institution minagri`) exits 1 at startup:
    `Parameter 0 of method api in rw.gov.risa.sso.wrapper.security.SecurityConfig required a bean
    of type 'org.springframework.security.config.annotation.web.builders.HttpSecurity'` (the CLI
    runs without a web server, but SecurityConfig is still loaded).

## Gaps

- Wrapper ops commands fail as above, so `make delegated-*`, `fgap-plan*`, `registry-validate`,
  `mfa-*`, `verify`, the CI jobs that call them and the MFA overlay's `mfa-install` cannot pass yet.
- Every failed login through the wrapper is audited twice (`auth.login.failed` from the wrapper with
  the client IP and from the Keycloak `gorsso-audit` listener with the wrapper's IP), so the
  brute-force count doubles (6 attempts -> count 12). Dedupe belongs to the wrapper/listener owners.
- The legacy UPPERCASE groups and clients of the reference realm JSON are still imported next to
  the canonical `minagri`/`minisante`; normalising them is the wrapper's reconcile, not the import.
- The authorization-services defaults (`Default Policy`/`Default Permission`) remain on seeded
  clients, as with the Terraform module.
