# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

GoRSSO / SSO.RISA — the Government of Rwanda single sign-on platform, rebuilt on Spring Boot 3.5 +
React 18 from a Python/FastAPI + Next.js reference. **Keycloak 26.2 only** (features
`organization,admin-fine-grained-authz:v2,recovery-codes` frozen at `kc.sh build`). Keycloak
authenticates; the **wrapper** (`sso-wrapper`) fronts it with RBAC-gated REST endpoints, drives the
Keycloak Admin API, and runs gates that can refuse a token Keycloak already minted.

- Spec: `docs/SPEC.md` (behaviour, security rules and API contracts are the specification).
- Conventions: `docs/BUILD_CONVENTIONS.md`. Per-feature notes (code locations, endpoints, tests):
  `docs/implemented/*.md` — read the relevant one before changing a feature. Start with
  `docs/implemented/wrapper-core.md` for anything in the wrapper.
- Reference implementation (read-only, for "why" questions):
  `/home/imfura/Data/CURIE_RISA/sso/codebase/sso`.

## Commands

```bash
mvn -q -B -N install                        # once: install the parent POM
mvn -B install                              # all Java modules + unit tests (no Docker)
mvn -B -Pit install                         # + Testcontainers suites (Keycloak 26.2, Postgres, Redis)
mvn -q -B -f sso-wrapper/pom.xml verify     # one module
mvn -q -B -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dopenapi.update=true   # refresh contract

export PATH=$PWD/.tools/node/bin:$PATH      # Node is NOT on the system PATH; use the repo copy
(cd console-web && npm run typecheck && npm run lint && npm run build && npm test && npm run test:e2e)
(cd console-web && npm run gen:api)         # regenerate API types from sso-wrapper/openapi.json

make start && make seed                     # dev stack (deploy/docker-compose.base.yml + apps.yml)
make smoke                                  # API checks + headless browser login against the stack
make help                                   # all targets (fgap-plan[-check], delegated-*, tf-*, mfa-*, verify, sdk)

java -jar sso-wrapper/target/sso-wrapper-*.jar help        # ops commands (no web server)
java -jar sso-wrapper/target/sso-wrapper-*.jar verify --institution minagri --institution minisante
```

Dev URLs/credentials (ports are overridable with `HOST_PORT_*`): Keycloak 8080 (`admin`/`admin`),
wrapper 8000 (`/docs/api`), console 3001, audit 8001, notification 8002, ippis-mock 8003,
Postgres 5435, Redis 6380, MailHog 8025, Adminer 8081. Demo users (password `GorSsoDev#2026`):
`superadmin`, `minagri.admin`, `minisante.admin`, `<slug>.{hr,auditor,staff,planner,unassigned}`.

## Layout

Java modules (root `pom.xml`, Spring Boot 3.5.16, `--release 21`; the machine JDK is 25):
`mfa-policy` (pure shared TOTP rule, zero deps — loaded inside Keycloak), `keycloak-extensions`
(`gorsso-mfa.jar` SPI: step-up authenticator, recovery codes, audit listener), `sso-wrapper`,
`console-bff`, `audit-service`, `notification-service`, `ippis-mock`, `sso-spring-boot-starter`.
Not in the root build: `tools/seeder`, `sample-apps/api`.
Frontends: `console-web` (React console), `keycloak-theme` (Keycloakify), `sdk/react`, `sample-apps/web`.

### Wrapper layering (ArchUnit-enforced)

`api.<feature>` (thin controllers) → `service.<feature>` (all Keycloak/Redis/DB/HTTP) →
`keycloak` / `identity` (JPA mirror). `policy.<module>` is **pure Java** — no Spring, servlet,
Keycloak or HTTP imports, no Jackson annotations — and holds the rules (delegated, clientscope,
joinkey, clientaccess, employeeaccounts, accessmatrix(sheet), ippisperson, registry, setuplink,
overview, fgap, lifecycle). Put new decision logic in `policy`, not in services.

Shared services other features call (see `docs/implemented/shared-services.md`):
`ClientAccessService`, `InstitutionContextService` (+ `@CurrentInstitution InstitutionContext ctx`),
`ClientScopeService`, `JoinKeyService`, `MfaService`, `Mailer`, `SetupLinkService`,
`service.provisioning.AccessChangeNotifier` (the ONE access-change mailer), `KeycloakDirectory`.

## Rules that are easy to break

- **Keycloak admin client: fresh per unit of work, never cached** — `KeycloakAdminFactory.asServiceAccount(realm -> …)`
  or `asCaller(token, …)`. The service account is `sso-client` (client credentials), never the master admin.
  Delegated calls run **as the caller** (`DELEGATED_EXECUTE_AS_CALLER=true`) so FGAP v2 holds the
  boundary too; cross-cutting clients' `sso-access-*` policies use the service account after the wrapper's check.
- **Routes:** gating per path lives in `security/RouteRules` (ADMIN_TIER / SELF_SCOPED / AUTHENTICATED /
  PUBLIC). `/institution-admin/**` and `/employees/**` are deliberately NOT admin-tier; they resolve
  their own scope. A new top-level prefix must be added there on purpose.
- **Every token door runs all three gates** (access → join key → MFA) via `service.gateway.TokenGates`,
  including `refresh_token`. `EveryDoorRunsEveryGateTest` must keep passing.
- **Institution boundary:** every `/institution-admin/*` read/write carries the mandatory institution
  filter from `InstitutionContext`; cross-institution → 403 (404 for directory lookups). Missing/unsafe
  `templates.json` → 503 `delegation_unavailable` (fail closed). Denies beat allows.
- **Access policies are read by TYPE, not name**; `sso-access-denied-users` (negative) is checked first.
- **Audit actor comes from `CallerPrincipal`**, never a request body. Emit via
  `AuditEmitter.emit(AuditEvent.of(type)…)`; it never throws (Redis outbox on failure). In tests:
  `@MockitoBean(answers = Answers.CALLS_REAL_METHODS) AuditEmitter audit`.
- **Errors:** throw `ApiException` → `{"error","message","details"}`. Codes in `error/ErrorCodes`.
- **Wire JSON is snake_case per DTO** (`@JsonProperty`) to match the reference; don't change the global naming.
- **Secrets:** never log tokens/passwords; temporary passwords are never returned, logged or emailed.
  `*_FILE` support is an explicit list in `config/FileBackedSecretsPostProcessor`. staging/prod refuse
  to start with a missing secret (`RequiredSecretsValidator`).
- **application.yml feature blocks** are separate YAML documents (`---` + top-level `sso:`); a second
  `sso:` key in one document fails startup.
- **Ops commands** implement `cli/OpsCommand`; they run with no web server (`sso.ops-mode=true`), so
  web-only config must be `@ConditionalOnWebApplication` (as `SecurityConfig` is).
- **Generated artifacts with drift checks:** `sso-wrapper/openapi.json` (OpenApiContractTest),
  `audit-service/openapi.json`, `notification-service/openapi.json`, `configs/generated/fgap-v2-plan.json`
  (CRLF, byte-identical; `fgap-plan-check`). Regenerate, don't hand-edit. After changing wrapper
  endpoints also run `npm run gen:api` in `console-web`.
- **Isolation suite:** `rw.gov.risa.sso.wrapper.isolation.*IsolationTest`; CI fails if any class in
  `ci/isolation-required.txt` is missing or ran 0 tests. Add new cross-institution tests there.
- **Tests needing Docker** are `@Tag("it")` (excluded by default; `-Pit`). `support/KeycloakIT` is the
  shared Keycloak 26.2 harness (seeded minagri/minisante, real user tokens).
- The console decides nothing: guards and hidden buttons are cosmetic; the wrapper enforces every rule.

## Keycloak 26 gotchas

Health/metrics on management port **9000**; `KC_PROXY_HEADERS=xforwarded` (`KC_PROXY` is gone);
`KC_HOSTNAME` is a full URL; bootstrap admin is `KC_BOOTSTRAP_ADMIN_*`; heap via `JAVA_OPTS_KC_HEAP`;
a group's `subGroups` is always empty — read `/groups/{id}/children`; the feature is `organization`
(singular) and nothing is scoped by Organizations; sessions are persistent; `start --optimized`
refuses build options at runtime (staging/prod overlays `!override` Keycloak's environment).
`KEYCLOAK_SERVER_URL` (docker hostname) ≠ `KEYCLOAK_PUBLIC_URL`/`KEYCLOAK_LOCAL_ISSUER` (browser-facing).
Public clients cannot carry authorization services, so under `ACCESS_DEFAULT_DENY` they must be in
`ACCESS_ENFORCEMENT_EXEMPT_CLIENTS` (dev compose exempts only the two sample SPA clients).
