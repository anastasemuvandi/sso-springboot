# IPPIS sourcing, identity mirror, lifecycle and employee directory (`sso-wrapper`)

Spec §5.8, §5.9, §5.10, §7.9. Ports `ippis_*.py`, `db/identity_*`, `core/lifecycle.py`,
`api/{employees,ippis_webhook,lifecycle,institutions}.py`, `employee_directory.py`,
`institution_overview.py`, `scripts/{reconcile_ippis,process_ippis_leavers,seed_identity_mirror_dev}.py`.
Runbooks: `docs/runbooks/ippis-sync.md`, `docs/runbooks/create-sso-identity-db.md`.

## Endpoints

| Route | Gate | What |
|---|---|---|
| `GET /employees` | SELF_SCOPED (scope via `InstitutionContextService.resolveDirectoryScope`) | one row per **employment**, `summary.{total,linked,unlinked,ambiguous,unmapped,no_email}`; `search,status,account,unmapped,sort,dir,limit(1..200),offset`; `institution` only narrows (platform admin) — a delegated admin naming another institution gets 403; 120/min |
| `GET /employees/{employeeNumber}` | same | the feed-shaped record; **only place `nationalId` is returned**; scoped caller sees only their institution's `employments` (`scoped:true`); another institution's employee → **404**, never 403; 120/min |
| `GET /institutions` | ADMIN_TIER | registry vs Keycloak (case-insensitive group match — legacy UPPERCASE) vs mirror; statuses `unknown_code, not_onboarded, partial, unregistered_group, onboarded` with remedies; `mirror:false` when no DB; 60/min |
| `POST /offboarding/terminate` | ADMIN_TIER | `{user_id, reason}` → reference `OffboardingResult` + `roles_removed`, `client_grants_removed`. Actor = token username (`triggered_by` in the body is ignored). 404 / 409 `lifecycle_failed` (rolled back) |
| `POST /access/reassign` | ADMIN_TIER | `{user_id, remove_groups, add_groups, force_session_refresh, reason}` → `ReassignmentResult`; 422 `account_disabled` |
| `GET /lifecycle/audit[/{userId}]` | ADMIN_TIER | proxy of audit-service `/audit/events[/{id}]?category=LIFECYCLE` (API key server-side) |
| `POST /ippis/events` | PUBLIC (HMAC) | 503 `not_configured` without `IPPIS_WEBHOOK_SECRET`; `X-IPPIS-Signature: sha256=hex(HMAC(secret, X-IPPIS-Timestamp + raw body))` constant-time (401 `invalid_signature`); skew > `IPPIS_WEBHOOK_MAX_SKEW` → 401 `stale_timestamp`; Redis `SET NX` nonce (TTL 2×skew+60s) → 409 `replayed`, Redis down → 503; dispatch terminated → offboard, updated → re-hire check + sync |

Mirror-dependent endpoints answer 503 `identity_db_not_configured` when `IDENTITY_DATABASE_URL` is
unset (authorisation is resolved first).

## Code

| Package | Classes |
|---|---|
| `identity` | `IdentityDatabaseConfig` (`@Conditional(IdentityDatabaseCondition)`: Hikari DataSource, Flyway `classpath:db/identity` + table `flyway_identity_history`, JPA EMF/TM, `@EnableJpaRepositories`), `IdentityDatabaseProperties` (`sso.identity.*`, jdbc or `postgresql://` URL, `:@` refused), `IdentityStore` (jdbc + tx templates + repositories), entities `Sector/Institution/Employee/Employment/Job/EmployeeSsoLink` + repositories, `MatchState` |
| `resources/db/identity` | `V1__ippis_identity.sql` — the reference schema (append-only trigger on `job`, case-insensitive unique email, slug CHECK) |
| `service.ippis` | `IppisClient` (RestClient on JDK HttpClient, timeouts, SSL bundle for mTLS/CA), `IppisAuth` (api_key / oauth2 cached token / mtls), `TransientRetry` (Resilience4j, linear backoff, 5xx/429/IO only), `IppisJson` (wire → policy records), `IppisReconcileService` (delta/full/leavers, bounded concurrency, dispatch shared with the webhook), `IdentityMirrorWriter` (per-person tx upsert; reassignment + identifier-conflict refusals; job update set = end_date/reason only; short_code/is_onboarded INSERT-only; failures counted), `WatermarkStore` (Redis), `RunReport`, `IppisWebhookService`, `IppisScheduler` (`@Scheduled`+`@SchedulerLock("ippis-reconcile")`, off unless `IPPIS_SYNC_SCHEDULE_ENABLED`, never in ops mode), `IppisRegistryProvider` (over the shared `InstitutionRegistryHolder`), `DevRoster` + `DevMirrorSeeder` |
| `service.ippis.cli` | `IppisCommands` + `OpsCommand` beans `ippis-reconcile`, `ippis-leavers`, `seed-identity-mirror`; `SeedMirrorProfileRunner` (profile `seed-mirror`) |
| `service.lifecycle` | `LifecycleService` (terminate per `OffboardingPlanner`, rollback, reassign, `applyLeaver`, `rehireIfEligible` per `RehirePolicy`), `UserOps`/`KeycloakUserGateway` (one service-account client per unit of work; attribute writes never change `email`), `LeaverAccessStripper` → `ClientAccessLeaverStripper` (delegates to `ClientAccessService.removeNamedGrantsEverywhere`), `LifecycleAuditService`, results, `LifecycleErrors` |
| `service.directory` | `EmployeeDirectoryService` (native SQL listing/summary, JPA detail), `EmployeeViews`, `EmployeeFilters`, `InstitutionOverviewService` |
| `api.{employees,ippis,lifecycle,institutions}` | thin controllers |

Pure rules come from `policy.ippisperson` (person view, attribute projection, reconcile policy,
summary), `policy.lifecycle` (offboarding plan/rollback, reassignment plan, re-hire rule),
`policy.institutioncontext` (directory scope), `policy.registry`.

## Behaviour notes
* Attributes written: `institution` (slug via `ippis_codes`; unmapped/ambiguous left untouched),
  `position`, `national_id`, `phone`, `ippis_status`. `employee_number` is the join key (never
  rewritten). Email never written; no account ever created.
* One `ippis.sync.attributes` audit per changed user; one `ippis.sync.unmapped_institution` per run
  with codes; `lifecycle.offboard|reassign|rehire`; `ippis.webhook.event`.
* Watermark in Redis (`gorsso:ippis:sync:watermark`), shared by all instances.
* `--dry-run` writes nothing (no Keycloak, no mirror, no watermark, no unmapped audit).
* Config block: `application.yml` (separate YAML document, `sso.identity.*`, `sso.ippis.*`).

## Tests
Unit: `service/ippis/IppisReconcileServiceTest` (skipped/not_found/no_user, slug never raw, never
email, unchanged not written, unmapped one audit per run, ambiguous, leaver offboard strips
`inst:*` roles + named grants, mover not offboarded, already-disabled, re-hire / manual-disable kept,
dry run, error isolation), `IppisWebhookServiceTest` (signature over ts+body, stale both sides,
replay 409, Redis down 503, dispatch, 422), `service/lifecycle/LifecycleServiceTest` (removal +
audit actor, rollback, 404, reassign), `service/directory/EmployeeDirectoryServiceTest`,
`service/ippis/cli/IppisCommandsTest`, `api/employees/EmployeesControllerTest` (401/403 before 503,
admin-tier lifecycle, webhook 503).
`@Tag("it")`: `identity/IdentityMirrorIT` (Postgres 16: Flyway, trigger refuses UPDATE/DELETE, close
allowed, reassignment and identifier conflict refused, short_code INSERT-only, one row per
employment, scoped list, 404-not-403 detail with `nationalId`, dev roster idempotent),
`api/ippis/IppisWebhookRedisIT` (real Redis replay), `service/ippis/IppisClientWireMockIT`.

## Gaps
* No Keycloak-Testcontainer IT for the reconcile/offboarding (Keycloak is mocked at `UserOps`); no
  shared KeycloakIT harness existed when this was written.
* A client whose `sso-access-users` could not be rewritten during offboarding is logged, not rolled
  back and not listed in the audit (the account is already disabled).
* Mirror `raw`/`record_hash` are not kept (as the reference).
