# Notification service (spec §5.19)

Spring Boot 3.5 / Java 21 port of the reference `notification-service/` (FastAPI + Celery).
Module `notification-service/`, package `rw.gov.risa.sso.notification`, port **8002**.

## Code

| Path (under `notification-service/src/main/java/rw/gov/risa/sso/notification/`) | What |
|---|---|
| `detector/` | **Pure Java** rules, no Spring: `BruteForceRule`, `UnusualHoursRule`, `ImpossibleTravelRule`, `AnomalousAccessRule`, `Detector` (runs the enabled ones), `RuleConfigs` (reference `DEFAULT_CONFIGS` + merge rule), `CooldownPolicy`, `SubjectKeys`, `AlertText`, `Timestamps` |
| `audit/AuditClient` | Reads the audit trail over the audit service's HTTP API |
| `service/DetectionService` | One detector cycle with the watermark |
| `service/Alerter` | Cooldown check → persist alert → deliver every channel → one `notifications` row per delivery |
| `service/Schedulers` + `config/SchedulingConfig` | `@Scheduled` detector (every 60 s) and daily report (08:00 Africa/Kigali), both under ShedLock (JDBC, DB time) |
| `channel/` | `EmailChannel` (Spring Mail + Thymeleaf `templates/email/*.html`), `InAppChannel`, `WebhookChannel` (Slack "attachments" payload) |
| `api/` | Controllers + snake_case DTOs; `web/` API-key filter, correlation-id filter, error envelope |
| `repository/` | `JdbcTemplate` repositories |
| `src/main/resources/db/migration/V1__initial_schema.sql` | Flyway: `alerts`, `notifications`, `alert_configs`, `detector_state`, `processed_audit_events`, `shedlock` |
| `notification-service/openapi.json` | Committed contract (checked by `OpenApiStalenessTest`) |
| `notification-service/Dockerfile` | Multi-stage → `eclipse-temurin:21-jre`; build from the repo root: `docker build -f notification-service/Dockerfile .` |

## Endpoints

All except `/health`, `/actuator/*`, `/v3/api-docs`, `/docs` need `X-Notification-API-Key: $NOTIFICATION_API_KEY` (401 otherwise).

| Method + path | Notes |
|---|---|
| `GET /alerts?severity&acknowledged&rule_name&limit(1..500, 100)&offset` | newest first |
| `GET /alerts/{alert_id}` | 404 `Alert not found` |
| `PUT /alerts/{alert_id}/acknowledge` `{"acknowledged_by"}` | acknowledging re-arms the rule's cooldown for that subject |
| `GET /notifications?recipient&is_read&channel&alert_id&limit&offset` | delivery records; `status` ∈ sent/failed/**skipped**; `error`, `sent_at` added |
| `PUT /notifications/{notification_id}/read` | |
| `PUT /notifications/read-all?recipient=` | `{"marked_read": n}` |
| `GET /config` | effective config of all 6 rules; `id`/`updated_at` null when not overridden |
| `PUT /config/{rule_name}` | partial update (only fields present); 404 unknown rule; 422 on bad severity/channel/negative numbers |
| `GET /reports/daily`, `POST /reports/daily/send` | added: the daily report as JSON, and a manual send |
| `GET /health` | `{"status":"ok","service":"notification-service"}` |

## How it reads audit events

Same as the reference: `GET {AUDIT_SERVICE_URL}/audit/events` with `X-Audit-API-Key: {AUDIT_API_KEY}`,
reading `items[]` of `{"total","limit","offset","items"}` (fields `id, event_type, user_id, ip_address, app_id, created_at`).
The reference sent `since=` (not a parameter of the audit API, so it got the newest 1000 rows); this
port sends `from_date`/`to_date` and pages with `offset`. Anomalous access asks
`?user_id&app_id&event_type=auth.login.success&to_date=<login>&limit=2`.

**Watermark:** each cycle reads `[W − 30 min, now − 2 s]` (30 min = longest rule window, so bursts across cycles count);
only events with `created_at ≥ W − 2 min` whose id is not in `processed_audit_events` are "new", and only new
events can trigger a rule. Then `W := now − 2 s`. An audit-service failure aborts the cycle without moving `W`.

## Rules (defaults; overridable through `/config`)

| Rule | Trigger | Sev | Channels | Cooldown |
|---|---|---|---|---|
| brute_force | ≥5 `auth.login.failed`/`login_failure` per user **or** per IP in 10 min (inclusive) | CRITICAL | email, inapp, webhook | 5 min (`CRITICAL_COOLDOWN_MINUTES`) |
| unusual_hours | successful login with Kigali hour <6 or ≥22 (`TIMEZONE_OFFSET_HOURS=2`) | WARNING | inapp | 60 min (`WARNING_COOLDOWN_MINUTES`) |
| impossible_travel | ≥2 distinct IPs for one user's successful logins in 30 min | CRITICAL | email, inapp, webhook | 5 min |
| anomalous_access | first successful login for a (user, app) pair | WARNING | inapp | 1440 min |
| stale_session, multiple_sessions | disabled placeholders (need Keycloak sessions), as in the reference | | | |

Cooldown: suppressed if an **un-acknowledged** alert for the same rule + subject exists at/after `now − cooldown`.

## Deliberate differences from the reference

- Skipped channels are recorded as `skipped` with a reason (reference: email/webhook "not configured" was recorded `sent`; in-app with no admins wrote nothing).
- Cooldown key is the real subject (`user:x`, `ip:x`, `user:x|app:y`) instead of `user_id`-else-`ip`; an IP cluster that is one user's failures is not alerted twice.
- First `PUT /config` for a rule starts from that rule's defaults (reference started from ORM defaults, downgrading brute_force to WARNING).
- Thresholds/windows from `/config` are honoured by the rules (the reference hard-coded 10/30 min and threshold 5 in the worker).
- Webhook URL (a secret) is stored as `scheme://host/…` only.

## Configuration (env)

`NOTIFICATION_API_KEY`, `AUDIT_SERVICE_URL`, `AUDIT_API_KEY`, `NOTIFICATION_DATABASE_URL` (reference SQLAlchemy form, translated)
or `NOTIFICATION_DATABASE_JDBC_URL`/`_USER`/`_PASSWORD`, `SMTP_HOST` (empty = email skipped), `SMTP_PORT`, `SMTP_USER`,
`SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_USE_TLS` (STARTTLS), `ADMIN_EMAILS` (or `ADMIN_EMAILS_RAW`), `WEBHOOK_URL`,
`TIMEZONE_OFFSET_HOURS`, `BUSINESS_START_HOUR`/`BUSINESS_END_HOUR`, `CRITICAL_COOLDOWN_MINUTES`, `WARNING_COOLDOWN_MINUTES`,
`DETECTOR_ENABLED`, `DETECTOR_INTERVAL_MS`, `DETECTOR_INITIAL_LOOKBACK_MINUTES`, `DETECTOR_OVERLAP_MINUTES`,
`DETECTOR_SETTLE_SECONDS`, `AUDIT_PAGE_SIZE`, `AUDIT_MAX_PAGES`, `AUDIT_TIMEOUT_SECONDS`, `DAILY_REPORT_ENABLED`,
`DAILY_REPORT_CRON`, `DAILY_REPORT_ZONE`, `LOG_LEVEL`. Every one accepts `<NAME>_FILE`. Profiles `staging`/`prod`
(or `NOTIFICATION_REQUIRE_SECRETS=true`) refuse to start with a missing/placeholder API key, audit key or SMTP password.
Logs are JSON (logstash encoder, `correlationId` in MDC); `SPRING_PROFILES_ACTIVE=plain-logs` for text.

## Tests

`mvn -B -f notification-service/pom.xml verify` (92 tests) / `-Pit verify` (94, needs Docker):

- `detector/*Test` — window edges (inclusive at exactly 10/30 min, excluded just past), Kigali boundaries
  05:59:59/06:00/21:59:59/22:00/midnight, cooldown edge, merge semantics, straddling bursts, IP-vs-user clusters.
- `service/AlerterTest`, `DetectionServiceTest`, `ConfigServiceTest`, `channel/*Test`, `config/SecretConfigTest`, `audit/AuditClientParsingTest`.
- `api/OpenApiStalenessTest` — regenerate with `-Dtest=OpenApiStalenessTest -Dopenapi.update=true`.
- `it/DetectorEndToEndTest` (`@Tag("it")`; Testcontainers postgres:16-alpine, WireMock audit API + webhook, GreenMail SMTP):
  audit events → `runOnce()` → 4 alerts persisted → email/in-app/webhook rows `sent` and the mails/webhook POSTs really
  received → REST API; then watermark (no re-processing), cooldown, acknowledge re-arms, config disable, read/read-all, daily report.
- `it/ScheduledDetectorTest` — the real `@Scheduled` + ShedLock path takes the lock and moves the watermark.
