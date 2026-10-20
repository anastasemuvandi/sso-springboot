# audit-service: hash-chained, append-only audit log (spec §5.18)

This module ports the reference Python service at `sso/audit-service` (FastAPI, SQLAlchemy and Celery) rule for rule. It uses Spring Boot 3.5, Java 21, plain JDBC, Flyway and Postgres 16, and replaces Celery with `@Scheduled` + ShedLock (JDBC). The service listens on port **8001**, as the reference does.

## Code map (`audit-service/`, package `rw.gov.risa.sso.audit`)

| Path | What |
|---|---|
| `chain/HashChain` | The SHA-256 formula (v1/v2), ported byte for byte. Pure Java. |
| `chain/PyJson` | Port of Python `json.dumps(sort_keys=True, default=str)`, including `float.__repr__`. |
| `chain/ChainVerifier` | Streaming verifier: hash and link checks, window anchor, retention-gap bridging, tail-vs-head check. |
| `chain/EventCategories` | `infer_category` (event-type prefix → category). |
| `service/AuditWriter` | The only path into `audit_logs`. Takes the chain-head lock, hashes, inserts and advances the head. |
| `service/EventService` | Ingest (lifts institution/target out of `details`) plus list/user reads. |
| `service/IntegrityService` | Verifies in one REPEATABLE READ snapshot, streaming rows through a cursor. |
| `service/RetentionService` | Policies, legal hold and the purge. Policy changes and purges are themselves audited. |
| `service/ExportService`, `ReportService` | CSV, PDF (OpenPDF) and the JSON reports. |
| `repository/*` | JDBC over `audit_logs`, `audit_chain_head`, `audit_purged_links`, `retention_policies`, `service_health`, `cert_expiry_tracking`. |
| `workers/*` | Health check, certificate expiry (Keycloak Admin API), access review, retention. |
| `web/ApiKeyFilter`, `web/CorrelationIdFilter` | `X-Audit-API-Key` auth (constant-time) and `X-Correlation-Id` → MDC `correlationId`. |
| `config/*` | Properties, `*_FILE` secrets and `DATABASE_URL` translation, staging/prod secret check, ShedLock, OpenAPI. |
| `src/main/resources/db/migration/V1..V3` | Schema (reference revisions 001+002), append-only triggers plus chain head, ShedLock table. |
| `openapi.json` | Committed contract. `OpenApiSpecTest` fails when it is stale. |
| `Dockerfile` (+ `Dockerfile.dockerignore`) | Multi-stage build (maven → `eclipse-temurin:21-jre`), non-root. Build it from the repo root. |

## Endpoints

Every `/audit/**` route requires the `X-Audit-API-Key` header. A missing or wrong key returns `401 {"error":"unauthorized",...}`. The paths, query parameters and JSON field names (snake_case) are the reference's.

| Method | Path | Notes |
|---|---|---|
| POST | `/audit/events` | Ingest → `201` + `AuditEventRead` |
| GET | `/audit/events` | Filters: `event_type, category, user_id, app_id, realm, institution, target, severity, from_date, to_date, limit` (1–1000, default 100), `offset`. Returns `{total, limit, offset, items}`, newest first |
| GET | `/audit/events/{user_id}` | `from_date, to_date, limit` (1–1000, default 200) → `[AuditEventRead]` |
| GET | `/audit/export/csv` | Reference filters plus `realm, institution, target, severity`. `limit` 1–50 000 (default 10 000). Returns an attachment `audit_export_YYYYMMDD_HHMMSS.csv` |
| GET | `/audit/export/pdf` | Same filters. `limit` 1–20 000 (default 5 000). Returns an `application/pdf` attachment |
| GET | `/audit/integrity/verify` | `from_date, to_date` → `{valid, total_checked, tampered_count, first_tampered_id, first_tampered_at, checked_from, checked_to, tail_matches_head}` |
| GET | `/audit/retention/policies` | Default policy (`category: null`) first |
| POST | `/audit/retention/policy` | `{category, retention_days ≥ 0, legal_hold}`: an upsert, audited as `admin.audit_retention.policy_set` |
| POST | `/audit/retention/apply` | Purges now → `{deleted, message}`. Audited as `admin.audit_retention.applied` when anything was deleted |
| GET | `/audit/health/services` | `limit` 1–500 (default 50) |
| GET | `/audit/health/services/{service_name}` | `limit` 1–1000 (default 100) |
| GET | `/audit/reports/cert-expiry` | `days_ahead` 1–365 (default 90) → `{days_ahead, total, expiring:[{user_id, employee_id, email, cert_expiry, days_remaining}]}` |
| GET | `/audit/reports/access-analytics` | `from_date, to_date` → `{top_apps:[{app_id, access_count}], failed_login_trend:[{day, count}], total_access_denials}` |
| GET | `/audit/reports/compliance` | PDF. Defaults to the last 30 days |
| GET | `/health` | No key. Returns `{"status":"ok","service":"audit-service"}` |
| GET | `/actuator/health`, `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` | No key. Readiness includes the DB |
| GET | `/openapi.json`, `/docs` | springdoc |

The `from_date`/`to_date` parameters accept ISO-8601 with an offset or `Z`, a naive datetime (treated as UTC), or a bare date. A `+` that arrives URL-decoded as a space is repaired. Validation failures return `422 {"error":"validation_error","message":...,"details":{"fields":{...}}}`.

### Ingest payload (what the wrapper and the Keycloak listener send)

```http
POST /audit/events
X-Audit-API-Key: <AUDIT_API_KEY>
Content-Type: application/json
X-Correlation-Id: <optional, echoed back and logged>

{
  "event_type":   "auth.login.success",       // required, ≤100 chars; prefix → category
  "user_id":      "jdoe",                     // ≤255
  "employee_id":  null,                       // ≤100
  "app_id":       "minagri-kubaka-web",       // client id, ≤100
  "realm":        "government-internal",      // ≤100
  "institution":  "minagri",                  // slug; if null/absent → details.institution
  "target":       "jdoe",                     // if null/absent → details.target_user, then details.target
  "performed_by": "jdoe",                     // the actor: always the authenticated principal (§7.7)
  "ip_address":   "10.0.0.1",                 // ≤45
  "details":      {"outcome": "success", "session": "…"},   // any JSON object; default {}
  "severity":     "INFO"                      // INFO | WARNING | CRITICAL (case-sensitive), default INFO
}
```

Unknown properties are ignored. The reference wrapper sets `severity` to `WARNING` when the outcome is `failure` or `denied`, puts `outcome` and `session` into `details`, and maps its internal event names to dotted types (`login_success` → `auth.login.success`, `login_denied` → `authz.access.denied`, `token_refreshed` → `session.refreshed`, …; see the reference `src/app/core/audit.py`). There is no `outcome` column: the outcome travels in `details.outcome`, as in the reference. The response is `201` with the stored event, including `id`, `category`, `prev_hash`, `event_hash`, `hash_version` and `created_at`. Categories are `AUTHENTICATION`, `AUTHORIZATION`, `ADMIN`, `LIFECYCLE`, `SESSION`, `CERTIFICATE` and `UNKNOWN` (any other prefix, e.g. `provision.user`).

## Hash chain: canonical form

`event_hash = sha256_hex(utf8(join("|", fields)))`. A null field is the empty string.

- **v1:** `prev_hash | event_type | user_id | created_at | details`
- **v2 (written now):** v1 followed by `| performed_by | institution | target | app_id | ip_address | severity`

The two non-trivial fields:

- **`created_at`** is Python `datetime.isoformat()` of the UTC instant: `2026-10-20T09:15:30.123456+00:00`. The fraction is exactly 6 digits and is dropped when it is zero (`…:30+00:00`). Timestamps are truncated to microseconds, Postgres precision, before both hashing and storing.
- **`details`** is Python `json.dumps(d, sort_keys=True, default=str)`. That means keys sorted by code point at every level, `", "` and `": "` separators, every non-printable-ASCII character escaped as `\uXXXX` (lower-case hex, surrogate pairs), floats in `repr` form (`1.0`, `1e-05`, `1e+16`), and `true`/`false`/`null`.

Before hashing, `details` is round-tripped through `jsonb`, so the writer hashes exactly what the verifier will read back. Rows keep their `hash_version`, and the verifier uses the same dispatch, so old v1 rows still verify. Golden vectors generated by CPython from the reference formulas check this (`GoldenVectors`, `HashChainTest`), along with a corpus of about 3 800 Python `repr(float)` outputs (`py-float-repr.txt`).

**Append and concurrency.** One transaction does the following: it reads `SELECT … FROM audit_chain_head WHERE id = 1 FOR UPDATE`, sets `created_at = max(now, head + 1µs)`, hashes against the head, inserts the row and advances the head. Concurrent writers queue on the head row, so the chain cannot fork. The reference locked the newest `audit_logs` row instead, which forks on an empty table and under READ COMMITTED re-checks. `ChainIT.concurrentWritersNeverForkTheChain` checks this with 8 threads × 25 events: no `prev_hash` repeats and the chain verifies.

**Append-only (V2 triggers).** `audit_logs` refuses UPDATE and DELETE with `restrict_violation`, and refuses TRUNCATE. The only exception is the retention purge, which runs `set_config('gorsso.audit_retention_purge','on', true)` inside its own transaction. Each purged row then leaves a hash-only link in `audit_purged_links`, which is insert-only. The verifier accepts a `prev_hash` gap only through such links. Any other deletion is reported, and deleting the newest rows makes `tail_matches_head` false. These triggers stop application bugs and accidental edits. They do not stop a database superuser, who can disable triggers. For that, export the head hash to an external store periodically (not done here).

## Scheduled workers (ShedLock JDBC, `shedlock` table, DB time)

| Job | Schedule (Africa/Kigali) | Lock name / lease |
|---|---|---|
| Health check: keycloak, wrapper, admin-ui (HTTP), postgresql (`SELECT 1`), redis (RESP `PING`) → `service_health` | every 60 s | `audit.health_check`, at most 2 min |
| Certificate expiry: Keycloak `certExpiry` attribute → `cert_expiry_tracking`, plus `cert.expiry_warning` at 90/60/30 days | 06:00 daily | `audit.cert_expiry`, at most 30 min |
| Retention purge (plus pruning `service_health` older than 90 days) | 02:00 daily | `audit.retention`, at most 1 h |
| Access review: one `admin.access_review.due` (WARNING, actor `system:access-review`) per institution, with a 92-day summary | 07:00 on 1 Jan/Apr/Jul/Oct | `audit.access_review`, at most 30 min |

`AUDIT_WORKERS_ENABLED=false` turns the workers off in a replica.

## Environment

| Variable | Default | Notes |
|---|---|---|
| `PORT` | 8001 | |
| `APP_ENV` | dev | `staging`/`prod` refuse to start without a real API key |
| `AUDIT_API_KEY` (`_FILE`) | none | Required. A missing key in dev means every request is rejected |
| `AUDIT_API_KEYS` (`_FILE`) | none | Extra comma-separated keys, for rotation |
| `DATABASE_URL` (`_FILE`) | none | Reference form `postgresql+asyncpg://user:pass@host:5432/db`, or `jdbc:…` |
| `DATABASE_USER`, `DATABASE_PASSWORD` (`_FILE`) | none | Override the credentials in the URL |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | `jdbc:postgresql://postgres:5432/sso_risa` | Take precedence over `DATABASE_URL` |
| `DATABASE_POOL_SIZE` | 20 | |
| `RUN_MIGRATIONS` | true | Flyway. Baselines at 0 to adopt a schema the Python service created |
| `AUDIT_WORKERS_ENABLED` | true | |
| `SERVICES_TO_MONITOR` | `keycloak,wrapper,admin-ui,postgresql,redis` | |
| `KEYCLOAK_HEALTH_URL` | `http://keycloak:9000/health/ready` | |
| `WRAPPER_URL` / `WRAPPER_HEALTH_URL` | `http://wrapper:8000` + `/actuator/health` | |
| `ADMIN_UI_URL` / `ADMIN_UI_HEALTH_URL` | `http://console-bff:8080` + `/actuator/health` | |
| `AUDIT_HEALTH_URLS_<NAME>` | none | URL for any extra monitored service |
| `REDIS_URL` (`_FILE`) | none | `redis://[:pass@]host:port/db`. Unset → reported `UNKNOWN` |
| `HEALTH_CHECK_TIMEOUT` | 5s | |
| `KEYCLOAK_SERVER_URL`, `KEYCLOAK_REALM` | `http://keycloak:8080`, `government-internal` | Certificate-expiry worker |
| `KEYCLOAK_ADMIN_USERNAME`, `KEYCLOAK_ADMIN_PASSWORD` (`_FILE`) | admin, none | Password grant on `KEYCLOAK_ADMIN_REALM` (master) / `KEYCLOAK_ADMIN_CLIENT_ID` (admin-cli). With no password set, the job skips |
| `SERVICE_HEALTH_RETENTION_DAYS` | 90 | |
| `AUDIT_WORKERS_HEALTHINTERVAL`, `…_CERTEXPIRYCRON`, `…_RETENTIONCRON`, `…_ACCESSREVIEWCRON` | see above | Cron jobs: `-` disables the job; the health interval is an ISO duration |
| `LOG_LEVEL`, `LOG_FORMAT` | INFO, `logstash` | JSON logs carry `correlationId` |

## Tests

- `mvn -B -f audit-service/pom.xml verify` runs the unit tests and needs no Docker. They cover:
  - `PyJsonTest`, `HashChainTest`, `ChainVerifierTest` (tampered field, details, hash, deleted middle row, deleted tail, mixed v1/v2, window anchor, purge bridging), `EventCategoriesTest`
  - `DateParamsTest`, `ApiKeyFilterTest`, `FileBackedSecretsPostProcessorTest`, `RequiredSecretsValidatorTest`, `ExportServiceTest`, `EventServiceTest`, `KeycloakUserSourceTest`
  - `OpenApiSpecTest`: the full context with a mocked DataSource, comparing against the committed `openapi.json`. Regenerate with `-Dtest=OpenApiSpecTest -Dopenapi.write=true`
- `mvn -B -f audit-service/pom.xml -Pit verify` adds the Testcontainers suites (`@Tag("it")`, `postgres:16-alpine`):
  - `ChainIT`: linking, jsonb round-trip rehash, concurrent append, triggers, purge flag and links, tamper and heal, tail delete, legacy v1 rows, windows
  - `ApiIT`: auth, the reference test cases, lift from details, institution filter, 422s, CSV/PDF/compliance, verify, retention plus legal hold plus audit-of-retention, reports, actuator, correlation id
  - `WorkersIT`: the health check, certificate-expiry thresholds, re-run and renewal, the access-review summary, the ShedLock row

## Deviations from the reference (deliberate)

- **Chain lock:** a chain-head row instead of `FOR UPDATE` on the newest row.
- **New protection:** the DB triggers, purge links and the tail-vs-head check are new.
- **Windowed verify:** it anchors on the row before `from_date`. The reference flagged the first row of every window as tampered.
- **Retention policies:**
  - Retention gaps are bridged; the reference reported every purge as tampering.
  - Saving the default policy no longer creates duplicate `NULL` rows (unique index `NULLS NOT DISTINCT`).
  - `retention_days < 0` is now a 422.
- **Certificate worker:**
  - It finds tracking rows by `user_id`; the reference crashed on its second run.
  - A renewed certificate resets the 90/60/30 flags.
  - It pages through all users instead of stopping at the first 1 000.
- **Exports:**
  - CSV `details` is JSON, not a Python dict repr.
  - The CSV gains `institution, target, hash_version, prev_hash, event_hash`.
  - Formula-leading cells get a `'` prefix.
  - Exports accept the institution, target, realm and severity filters.
- **Access analytics:** `total_access_denials` respects the date window.
- **Error format:** errors use the platform envelope, not FastAPI's `{"detail": …}`. Callers that parsed `detail` need to read `message`.
- **CORS:** off. Browsers never call this service; the wrapper proxies it. The reference allowed `*`.
- **Health config:** `SERVICE_HEALTH_URLS` (a JSON dict) is replaced by `AUDIT_HEALTH_URLS_<NAME>`. The default health URLs target Keycloak on `:9000` and the Spring services on `/actuator/health`.
