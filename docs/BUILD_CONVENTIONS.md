# Build conventions (for everyone working in this repo)

The specification is `docs/SPEC.md` (a copy of PROMPT_SSO_REBUILD_SPRINGBOOT_REACT.md).
The **reference implementation** being ported (Python FastAPI wrapper + Next.js console) is at
`/home/imfura/Data/CURIE_RISA/sso/codebase/sso` (read-only — never modify it). When the spec
is terse, port the reference's behaviour rule-for-rule; the reference's docstrings explain
the *why* of most rules. The spec wins where they disagree.

## Layout

| Path | What |
|---|---|
| `pom.xml` | Maven parent (Spring Boot 3.5.16, Java release 21). Lists every Java module. |
| `mfa-policy/` | Pure-Java shared TOTP rule (`rw.gov.risa.sso.mfa.policy`). No dependencies. Used by the wrapper and the Keycloak SPI. |
| `keycloak-extensions/` | `gorsso-mfa` Keycloak SPI (authenticators + audit event listener). Shades `mfa-policy`. |
| `sso-wrapper/` | The wrapper API (`rw.gov.risa.sso.wrapper`). |
| `console-bff/` | BFF for the console (`rw.gov.risa.sso.bff`). |
| `audit-service/` | Hash-chained audit log (`rw.gov.risa.sso.audit`). |
| `notification-service/` | Detector + channels (`rw.gov.risa.sso.notification`). |
| `ippis-mock/` | IPPIS (GESB) mock (`rw.gov.risa.sso.ippismock`). |
| `sso-spring-boot-starter/` | Starter for integrated apps (`rw.gov.risa.sso.starter`). |
| `console-web/` | React 18 + TS + Vite console. |
| `keycloak-theme/` | Keycloakify login/email theme. |
| `sdk/` | Generated SDKs (Java, TypeScript) + the hand-written React SDK. |
| `sample-apps/` | Demo Spring Boot API + React SPA for `minagri` / `minisante`. |
| `configs/` | `institutions.yaml`, `delegated-permissions/templates.json`, `realms/`, `generated/fgap-v2-plan.json`. |
| `infra/terraform/` | Onboarding-as-code. |
| `keycloak/Dockerfile` | Keycloak 26.2 image with providers + theme. |
| `deploy/` | Docker Compose files. |
| `docs/implemented/<feature>.md` | One short note per feature: code locations, endpoints, pages, tests. |

## Java rules

- JDK on this machine is 25; we compile with `--release 21`. Do **not** use Lombok (breaks on
  new JDKs). Use records for DTOs, constructor injection, `final` fields.
- Once per machine/session: `mvn -q -B -N install` (installs the parent POM).
- Build ONE module: `mvn -q -B -f <module>/pom.xml verify` (parent resolves via `../pom.xml`).
  If you depend on `mfa-policy`, first `mvn -q -B -f mfa-policy/pom.xml install -DskipTests`.
  Do not edit the root `pom.xml` except to add a managed dependency version if truly needed
  (keep the edit minimal; other people are editing in parallel).
- Tests that need Docker (Testcontainers) are tagged `@Tag("it")` and excluded by default;
  `mvn -Pit verify` runs them. Docker IS available here (images present: `quay.io/keycloak/keycloak:26.2`,
  `postgres:16-alpine`, `redis:alpine`, `node:22-alpine`), so do run your IT tests at least once.
- JSON property naming on the wire is **snake_case** where the reference API uses it — copy the
  field names from the reference `openapi.json` (`/home/imfura/Data/CURIE_RISA/sso/codebase/sso/openapi.json`)
  and its pydantic schemas (`src/app/schema/*.py`). Use `@JsonProperty`/`@JsonNaming` per DTO;
  do not change the global Jackson naming strategy.
- Error envelope everywhere: `{"error": "<machine_code>", "message": "...", "details": {...}}`.
- Secrets: support `<NAME>_FILE` env vars; never log secrets/tokens/passwords.
- Structured JSON logs with a correlation id (`X-Correlation-Id`, MDC key `correlationId`).

## Frontend rules

- Node is NOT on the system PATH. Use the repo-local copy: `export PATH=$PWD/.tools/node/bin:$PATH`
  (from the repo root). npm registry access works.
- React 18, TypeScript strict, Vite, Tailwind + shadcn/ui, TanStack Query/Table, RHF + zod,
  react-i18next (en + rw). Vitest + RTL + MSW; Playwright for gates.

## Process

- Stay inside the directories you were assigned. Do not run `git commit`.
- Finish with: the module builds, its tests pass, and a `docs/implemented/<feature>.md` exists.
- Report back: what you built, what's tested, what's stubbed or left out (be honest).
