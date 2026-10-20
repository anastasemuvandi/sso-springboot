# Implementation status (2026-10-20)

Port of the reference GoRSSO platform (`docs/SPEC.md`) to Spring Boot 3.5 + React 18 on Keycloak 26.2.
Each feature's code locations, endpoints, pages and tests: `docs/implemented/*.md`.

## Definition of done (spec §9)

| Item | Status | Evidence |
|---|---|---|
| Every endpoint in §4 with §5 semantics | Done | 171 operations in `sso-wrapper/openapi.json` (committed, drift-checked). The console's contract diff found no reference operation missing |
| Every §7 invariant has a passing test | Done | See the table below |
| Isolation suite + Keycloak-direct `verify` 9/9 against a Keycloak 26.2 Testcontainer | Done | 18 `*IsolationTest` classes, all in `ci/isolation-required.txt` and gated by `ci/check-isolation-reports.sh`; `PlatformOpsCommandsIT` / `FgapApplyVerifyIT` run `fgap-apply` + `verify` 9/9 |
| Console builds and passes its gates | Done | typecheck, lint, build, Vitest 260, Playwright 8 (`auth-guard`, `employee-selection`) |
| `docker compose up` brings up the full stack with seeded minagri and minisante | Done | Live run, all 11 services healthy. API smoke and a headless browser login through BFF → wrapper → Keycloak, as super admin and as `minagri.admin`. Brute-force alert fired. `make smoke` repeats it |

### Test totals (`mvn -B -Pit install`, all with Docker)

| Module | Tests | Failing |
|---|---|---|
| sso-wrapper | 1725 | 0 |
| mfa-policy | 133 | 0 |
| keycloak-extensions | 105 | 0 |
| audit-service | 101 | 0 |
| notification-service | 94 | 0 |
| ippis-mock | 45 | 0 |
| sso-spring-boot-starter | 40 | 0 |
| console-bff | 34 | 0 |
| console-web (Vitest / Playwright) | 260 / 8 | 0 |
| keycloak-theme (Vitest + Keycloak smoke) | 20 | 0 |
| sdk/react | 40 | 0 |

## Security invariants (spec §7)

| # | Invariant | Main tests |
|---|---|---|
| 1 | Institution boundary held twice (wrapper filter + Keycloak FGAP v2 as caller) | `isolation.*IsolationTest` (18 classes), `FgapKeycloakDirectIsolationTest` |
| 2 | Denies beat allows (templates and client access) | `policy.delegated`, `policy.clientaccess` tests, `AccessRevocationIsolationTest`, `ClientAccessService` ITs |
| 3 | No token without access, join-key and MFA gates, including refresh | `EveryDoorRunsEveryGateTest` (30 cases), `AuthGatewayKeycloakIT` |
| 4 | Confidential clients authenticate; auth codes single-use | `OAuth2ControllerTest`, `AuthGatewayKeycloakIT`, `RedisAuthCodeStore` atomic GETDEL |
| 5 | Temp passwords never disclosed; setup links hashed and single-use | `EmployeeProvisioningServiceTest` (response, logs, mail, audit), `policy.setuplink` tests, `SetupLinkService` ITs |
| 6 | Excel import applies only the server-side plan, re-checks, never revokes | `AccessMatrixTransferServiceTest`, `policy.accessmatrixsheet` tests |
| 7 | Audit actor from the authenticated principal | Lifecycle, provisioning and access tests; `CallerPrincipal` is the only actor source |
| 8 | Login, token and admin events reach the audit trail | `GatewayAuditTrailTest` and `HttpAuditEmitterTest` (real emitter against a stand-in audit service), live smoke |
| 9 | Offboarding removes roles and named grants; webhooks have replay protection | `IppisReconcileServiceTest`, `LifecycleServiceTest`, `IppisWebhookServiceTest`, `IppisWebhookRedisIT` |
| 10 | No secrets in logs; `*_FILE`; staging/prod require secrets | `FileBackedSecretsPostProcessor`, `RequiredSecretsValidator` (each service); `${VAR:?}` in staging/prod compose |

## Deliberate deviations from the reference

- **Access-change requests:** `/auth/login` requires a confidential client's secret. Redirect URIs match exactly (`REDIRECT_URI_ALLOW_WILDCARDS=false`), so legacy realm clients registered with `/*` must be made exact. `return_to` on the account pages is relative-only.
- **Legacy MFA flag:** junk values of `mfa_totp_enforced` read as `all` everywhere (fail closed; Keycloak's reading).
- **Error envelope:** the audit and notification services use the platform envelope instead of FastAPI's `{"detail"}`.
- **Bugs fixed while porting:**
  - Audit: date-windowed integrity checks no longer flag the first row of the window; the certificate worker no longer crashes on its second run; a chain-head row lock prevents forks.
  - Notifications: unconfigured channels are recorded as `skipped`; thresholds set through `/config` are honoured; the cooldown is per subject.
  - Access matrix: a bulk grant whose provisioning failed is now reported `failed`, not `applied`.
- **Wrapper password-login failures** are stored as `auth.gateway.login_failed`. Keycloak's listener records the counted `auth.login.failed`, and the gateway forwards the end user's IP as `X-Forwarded-For`. This prevents double counting in the brute-force rule.
- **Public SPA clients** cannot carry Keycloak authorization services. In dev only, the two sample SPA clients are in `ACCESS_ENFORCEMENT_EXEMPT_CLIENTS`; the staging and prod overlays reset that list.

## Known gaps and follow-ups

- **New departments and FGAP v2:** a department created after the last FGAP apply is invisible to that institution's admins at the Keycloak layer. The wrapper skips it with a WARN; re-apply via Terraform or `fgap-apply` (runbook). Follow-up: trigger a scoped re-apply when a platform admin creates a subgroup.
- **Untested end to end:** a browser TOTP enrolment/verify/recovery-code journey through the `browser-totp` flow (providers and flow install are tested; the UI journey is not). Also the PKCE sample SPAs against the live wrapper.
- **Wrapper API polish:**
  - `GET /institution-admin/users` returns no per-member roles (the reference did).
  - `GET /institution-admin/audit` `total` is per page.
  - About 50 responses are untyped maps in the contract; the console keeps hand-written types checked against the Java code.
- **Notification service:** `stale_session` and `multiple_sessions` remain disabled placeholders, as in the reference. Events committed more than 2 min late are not processed.
- **Policy writes:** writes to a client's access policies are serialised per JVM only; two wrapper instances can still race, as in the reference.
- **Not exercised in real life:** `.gitlab-ci.yml` has not been run on GitLab. The audit chain head is not exported to an external store.
- **Content review:** the Kinyarwanda text (console, theme, account pages, emails) needs a native-speaker review.
- **Realm JSON:** it still imports the reference's legacy UPPERCASE groups and legacy clients alongside the canonical ones.
