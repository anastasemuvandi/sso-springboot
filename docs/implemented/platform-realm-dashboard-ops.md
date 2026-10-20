# Platform realm, dashboard, audit proxy, delegated ops, admin views, ops commands

SPEC §4 (Realm, Dashboard, Delegated permissions, Audit proxy, Admin views), §5.2, §5.3, §5.7, §5.15–§5.17.
Ports `api/{realm,dashboard,audit,delegated_permissions,admin}.py`, `services/{platform_overview,
delegated_permissions_service,audit_client}.py` and `scripts/{apply_delegated_permissions,render_fgap_plan,
validate_institution_registry,kc26_spike(apply|verify)}.py`.

## Code map (`sso-wrapper`, package `rw.gov.risa.sso.wrapper`)

| Where | What |
|---|---|
| `api/realm/RealmController` | `/realm/*` (config, branding, logo, themes, SMTP, TOTP mode + exemptions, overview) |
| `api/dashboard/DashboardController` | `/dashboard/*` |
| `api/audit/AuditProxyController` | `/audit/*` read proxy (streams exports) |
| `api/delegated/DelegatedPermissionsController` | `/delegated-permissions*` |
| `api/admin/AdminViewsController` | `/admin/*` views (real Keycloak data; the reference was mock) |
| `service/platformops/PlatformOpsProperties` | `sso.platform-ops.*` (see application.yml block) |
| `service/platformops/realm/RealmSettingsService`, `RealmDtos` | realm/branding/logo/themes/SMTP/overview |
| `service/platformops/overview/PlatformOverviewService`, `OverviewView`, `OverviewCache` | `GET /dashboard/overview` |
| `service/platformops/dashboard/DashboardService` | metrics, counts, sessions, logins, activity, events |
| `service/platformops/audit/AuditServiceClient`, `AuditQuery` | audit-service READ client (reusable, see below) |
| `service/platformops/delegated/DelegatedOpsService` | describe / preview / reload / apply / audit |
| `service/platformops/admin/AdminViewsService` | `/admin/*` |
| `service/platformops/fgap/AdminRest`, `AdminSession`, `FgapApplier` | raw Admin REST client, admin identity, FGAP v2 applier |
| `service/platformops/verify/KeycloakDirectVerifier` | the 9-case Keycloak-direct acceptance test |
| `cli/*` | ops commands (`OpsCommand` beans) + `CliArgs`, `PrintingCommand`, `ConfigFiles` |

## Endpoints (all under the admin tier in `RouteRules`)

Reads: any platform admin (`super_admin|admin|system_admin`). Writes marked **SA** are `super_admin` only (403 otherwise).

| Method | Path | Notes |
|---|---|---|
| GET / PUT **SA** | `/realm/config` | GET = realm representation, `smtpServer.password` removed. PUT = `RealmConfig` DTO, only fields sent are written, renaming the realm → 400; audited `admin.realm.updated` (`section`, `changed`) |
| GET / PUT **SA** | `/realm/branding` | `{themes, display, custom_attributes}`; PUT leaves blank fields as they are |
| POST **SA** | `/realm/branding/logo` | multipart `file`; JPEG/PNG/GIF/SVG only, ≤ 5 MB (`REALM_LOGO_MAX_BYTES`), magic bytes must match the declared type, SVG with script/event handlers refused; stored as a `data:` URL in realm attribute `logo_url` |
| GET | `/realm/themes` | names from `/admin/serverinfo` |
| GET / PUT **SA** | `/realm/email/config` | Keycloak `smtpServer`; the password is NEVER returned; PUT without a password keeps the stored one (Keycloak's `**********` sentinel) |
| POST **SA** | `/realm/email/test` | Keycloak `testSMTPConnection` with the stored settings → `{success, message}`; retried with the caller's token when Keycloak says the service account has no e-mail |
| GET / PUT **SA** | `/realm/mfa` | `MfaService` (shared services) — mode `off|per_client|all` (or legacy `enforced`), audited actor/old/new/time by `MfaService` |
| GET / POST **SA** | `/realm/mfa/exemptions` | GET → `{"exemptions": [...]}` (as the reference and the console expect); POST `{user_id, reason}` → 201 |
| DELETE **SA** | `/realm/mfa/exemptions/{user_id}` | as the reference: no body/reason (the console sends none); `MfaService.revokeExemption` audits it |
| GET | `/realm/overview` | realm/security/themes/email/branding/localization summary |
| GET | `/dashboard/overview?refresh=` | see below |
| GET | `/dashboard/metrics` | `total_users, total_clients, total_agencies` (institution groups that are registry slugs), `active_sessions, logins_today, new_users_this_week` (created in 7 d) — nothing mocked |
| GET | `/dashboard/users/count`, `/dashboard/clients/count`, `/dashboard/sessions` | |
| GET | `/dashboard/logins/recent?max_items=` (1–100), `/dashboard/logins/stats?period=24h|7d|30d` | buckets in Africa/Kigali time |
| GET | `/dashboard/activity/24h`, `/dashboard/events?event_types=&max_events=`, `/dashboard/admin/overview` | `admin/overview.system_health` probes Redis |
| GET | `/delegated-permissions`, `/delegated-permissions/institutions/{slug}` | bad/unsafe config or slug → 400 |
| POST | `/delegated-permissions/reload`, `/delegated-permissions/apply` (`{institutions, dry_run}`) | apply audited `admin.delegated.applied` |
| GET | `/delegated-permissions/audit?institution=` | `{realm, source, guardrail_enforced, institutions, guardrail_violations, unmanaged_grants, missing_roles, clean}` |
| GET | `/audit/events`, `/audit/integrity/verify`, `/audit/retention/policies`, `/audit/export/{csv|pdf}` | proxy, see below |
| GET | `/admin/agencies/{agency_code}/dashboard` | `{agency_code, name, user_count (whole group tree), groups (departments + member_count), services (owned + cross-cutting covering it)}` |
| GET / POST | `/admin/agencies/{agency_code}/groups` | departments; POST `{name, description?}` creates a child group (audited) |
| GET | `/admin/clients/{client_id}/dashboard`, `/admin/clients/{client_id}/users` | access policy + client roles + sessions + last LOGIN event |
| POST | `/admin/clients/{client_id}/users/{user_id}/roles` | `{roles}` client roles; unknown → 400; `mfa-exempt` refused; audited |
| GET | `/admin/system/clients`; `/admin/system/users?limit=&offset=` (**SA**) | |

### `GET /dashboard/overview` (spec §5.15)

Response schema `PlatformOverview` (the reference's `Overview`; renamed in the contract because the
`/institutions` response record is also called `Overview`). Four INDEPENDENT sections built with
`policy.overview.Overview`, each with `available` + `error` — a failed read never shows as zero:
`leavers` (mirror realm-wide view, ≤ 200 accounts checked, `truncated`; unavailable "The IPPIS employee mirror
is not configured." without `IDENTITY_DATABASE_URL`), `password_setup` (UPDATE_PASSWORD pending, bucketed by
`MailDeliveryLog`: failed / not_configured / unknown / sent), `logins` (LOGIN/LOGIN_ERROR over 7 d, max
10,000 → `truncated`; `locked_now` from the brute-force detector for users with a failure in the last 15 min,
≤ 50), `access` (per client: `named`, `groups` not expanded, `revoked`, `active_sessions`; built-in and exempt
clients excluded; a client whose policies cannot be read is named in `error`). Cached 60 s in Redis
(`platform:overview`, falls back to instance memory when Redis is down); `?refresh=true` rebuilds.
`OverviewView.of(OverviewReport)` is public so `/institution-admin/overview` can return the same wire form.

### Audit proxy and `AuditServiceClient` (for the institution-admin team)

The API key (`AUDIT_API_KEY`) is added server-side. Upstream errors are surfaced with the platform envelope,
carrying the audit-service's `{error, message, details}`: 400/404/422 keep their status; 401/403 (bad key) →
502 `audit_service_error` (never a 401 to a browser); 5xx → 502; unreachable → 503 `upstream_unavailable`.
Exports are streamed (`StreamingResponseBody`) with the upstream `Content-Type` / `Content-Disposition`.

**Institution-scoped reads:** `AuditServiceClient.listEventsForInstitution(slug, AuditQuery)` and
`exportForInstitution(slug, fmt, AuditQuery)` OVERWRITE the query's `institution` with the caller's slug (blank
→ 403). Use these from `/institution-admin/audit`, never `listEvents`. (The institution-admin team currently has
its own `service/institutionadmin/InstitutionAuditReader`; switching is optional.)

## Ops commands (`java -jar sso-wrapper.jar <command>`)

| Command | What | Exit |
|---|---|---|
| `delegated-validate [--config P]` | parse + guardrail-check templates (no Keycloak) | 0 valid / 1 invalid or missing |
| `delegated-apply [--institution S]... [--dry-run] [--config P]` | create `inst:<slug>:*` roles (`RoleProvisioningPlan`), additive; default = configured + canonical top-level groups | 0 / 1 |
| `delegated-check [--institution S]... [--config P]` | guardrail violations (composite-expanded), unmanaged grants, missing roles | 0 clean / 1 findings |
| `fgap-plan [--stdout] [--output P] [--templates P] [--registry P]` | render `configs/generated/fgap-v2-plan.json` with **CRLF** (byte-identical to the committed file) | 0 |
| `fgap-plan-check [--plan P] ...` | `FgapPlanRenderer.isUpToDate` | 0 / 1 stale or missing |
| `registry-validate [--registry P] [--templates P] [--strict]` | `RegistryValidator` | 0 / 1 |
| `fgap-apply [--institution S]... [--plan P] [--dry-run] [--subgroups enumerate|propagate] [--admin-user U --admin-password P] [--url U] [--realm R]` | apply the FGAP v2 plan via the Admin API (enables admin permissions; role policies `required=false`; scope permissions UNANIMOUS; existing objects CONVERGED; unresolved objects reported, never widened). Default = projected against the realm's inventory; `--plan` = apply a rendered file. Terraform stays the prod path | 0 / 1 problems |
| `verify --institution MINE --institution THEIRS [--username U] [--password P] [--client-id C] [--client-secret S] [--department D]` | log in as a real institution admin (password grant; password from `--password`/`VERIFY_PASSWORD`; client defaults to `KEYCLOAK_CLIENT_ID`+secret, or `VERIFY_CLIENT_ID`/`VERIFY_CLIENT_SECRET`) and call the Keycloak Admin API DIRECTLY for the 9 `kc26_spike.py verify` cases; prints `N/9 passed`; a case that cannot be set up counts as FAILED; undoes the role assignment it made | 0 all 9 pass / 1 / 2 login failed |
| `mfa-install`, `mfa-enrol` | owned by shared services (`service/mfa/MfaCommands`, `MfaFlowInstaller`, `MfaBulkEnrolment`) — not duplicated here | |

Admin-API commands use the `sso-client` service account unless `--admin-user` (master `admin-cli`, password from
`--admin-password` or `KEYCLOAK_ADMIN_PASSWORD`).

## Tests

- MockMvc (`@WrapperTest`): `RealmControllerTest` (18: gates, write = super admin, SMTP password never returned,
  password kept, logo type/size/magic/SVG-script, TOTP/exemptions incl. `{exemptions: [...]}`),
  `DashboardControllerTest` (7: gate, every section's available/error, a failed read only affects its section,
  unreadable client named, cache + refresh, metrics), `AuditProxyControllerTest` (12, WireMock as the audit-service:
  key injected, 5xx→502, 401→502, 422 kept, unreachable→503, CSV/PDF streamed with disposition, institution pinned),
  `DelegatedPermissionsControllerTest` (8), `AdminViewsControllerTest` (7).
- Unit: `cli/OfflineCommandsTest` (11: fgap-plan-check passes on the committed file and fails on an altered temp copy
  and a missing file; fgap-plan reproduces the committed file byte for byte; delegated-validate rejects an unsafe
  template; registry-validate).
- `@Tag("it")` on the shared `support/KeycloakIT` (Keycloak 26.2): `cli/PlatformOpsCommandsIT` (6: delegated-apply
  idempotent + dry run; delegated-check clean then non-zero on a realm-management grant; **fgap-apply + verify 9/9**;
  verify exit 2 on bad login; verify exit 1 for an admin holding `realm-admin` (boundary broken); applying the
  committed plan to a 2-institution realm reports what it lacks), `service/platformops/fgap/FgapApplyVerifyIT` (2:
  apply converges idempotently, verify 9/9), `service/platformops/MfaInstallFlowIT` (own container with
  `keycloak-extensions/target/gorsso-mfa.jar` + `recovery-codes`: the installed `browser-totp` equals the definition,
  bound, mode stays `off`, second run finds nothing). Run: `mvn -B -Pit -f sso-wrapper/pom.xml verify` (or
  `-Dtest='PlatformOpsCommandsIT,FgapApplyVerifyIT,MfaInstallFlowIT'`).

**Definition of done:** the Keycloak-direct `verify` passes **9/9** against a Keycloak 26.2 Testcontainer.

## Gaps / deviations

- `DELETE /realm/mfa/exemptions/{user_id}` takes no reason (reference + console); the grant carries the reason.
- `POST /realm/email/test` uses Keycloak's endpoint, which mails the authenticated admin: with neither the service
  account nor the caller having an e-mail/`manage-realm`, the result is `success:false` with Keycloak's message.
- `/dashboard/metrics.new_users_this_week` walks every user (brief representation); fine for tens of thousands.
- `fgap-apply` converges role-policy role lists and permission resource lists; it does not delete managed objects
  that the plan no longer contains (drift is reported by Terraform / a future `fgap-check`).
- Contract schema for the overview is named `PlatformOverview` (see above).
