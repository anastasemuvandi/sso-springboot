# Runbook — Run the IPPIS sync (Spring Boot wrapper)

| | |
|---|---|
| **Applies to** | `sso-wrapper` — IPPIS reconcile, leaver offboarding, push webhook |
| **Code** | `service/ippis/*`, `service/lifecycle/*` (see `docs/implemented/ippis-and-directory.md`) |
| **Mirror** | optional — `docs/runbooks/create-sso-identity-db.md` |

## 1. How it works

### 1.1 The join key
Every IPPIS-managed Keycloak user carries the attribute `employee_number`
(`IPPIS_EMPLOYEE_NUMBER_ATTRIBUTE`). It is the IPPIS feed's top-level `id`. A user without it is
`skipped` — not IPPIS-managed. The sync **never creates an account** and **never writes the `email`
property** (the account's identity); provisioning (`POST /institution-admin/employees/provision`)
is the only creator.

### 1.2 One fetch, one decision per person
The person view (`policy.ippisperson.IppisPersonViews`) flattens `person → employments → jobs`:

* the PRIMARY employment (a current, non-secondary job) decides `institution`; ties break on the
  earliest start date; two primaries starting the same day = **ambiguous** → `institution` left
  unwritten and counted (`ambiguous_institution`);
* a **leaver has no open job anywhere** — resigning one of two posts is a *move*, not an exit.

Leaver → offboarding (`LifecycleService.terminate`): end sessions → disable (+ markers
`offboarded_by`/`offboarded_at`) → remove every group → strip `inst:<slug>:*` realm roles → remove
the person from every client's `sso-access-users`; audited `lifecycle.offboard` with exactly what was
removed; rolled back (re-enable, re-add groups/roles) on failure.

Not a leaver → **re-hire check** then attribute sync. Re-hire rule (`policy.lifecycle.RehirePolicy`):
a *disabled* account is re-enabled only if IPPIS shows an open post again AND it was disabled by the
feed (`offboarded_by` ∈ `ippis-reconcile`, `ippis-leaver-feed`). Hand-disabled accounts stay
disabled. Only the account is restored — roles, groups and grants are granted afresh. Audited
`lifecycle.rehire`.

### 1.3 Attributes written
`institution` (registry slug via `ippis_codes` in `configs/institutions.yaml` — never the raw code;
an unknown code leaves the attribute untouched and is reported), `position`, `national_id`, `phone`,
`ippis_status`. Written only when something changed; one `ippis.sync.attributes` audit per changed
user (`institution_before` on a move). `IPPIS_CLEAR_MISSING=true` additionally clears attributes IPPIS
stopped providing (never an unmapped/ambiguous institution). A legacy UPPERCASE `institution`
(`MINAGRI`) is rewritten to the slug on the next pass.

### 1.4 Outcomes
Per user: `updated`, `unchanged`, `disabled`, `already_disabled`, `active`, `skipped`, `not_found`
(IPPIS 404), `no_user` (delta record with no account — the joiner queue), `error`. Orthogonal
counters: `unmapped_institution`, `ambiguous_institution`, `mirrored`, `mirror_errors`,
`mirror_refused`. One `ippis.sync.unmapped_institution` audit (outcome failure) per run that saw any
unmapped code, listing the codes.

### 1.5 Delta vs full, watermark, locking
* **Delta** (`ippis-reconcile`): `GET /people?changed_since=<watermark>`; each person resolved to the
  Keycloak user(s) carrying the number (several = ambiguous, all written, recorded once). The new
  watermark is the max `updatedAt` seen (IPPIS's clock). Stored in **Redis**
  (`IPPIS_SYNC_WATERMARK_KEY`, default `gorsso:ippis:sync:watermark`) so every instance shares it.
  Delete the key to re-pull everything (bootstraps the mirror's joiner queue).
* **Full** (`ippis-reconcile --full`): every realm user, `GET /people/{n}` each; re-anchors the
  watermark to the run's start.
* Both take the ShedLock lease `ippis-reconcile` (Redis): a CLI run never overlaps a scheduled one,
  and a killed run's lease expires.
* `IPPIS_SYNC_CONCURRENCY` users in parallel; transient failures (network, 5xx, 429) retried
  `IPPIS_SYNC_MAX_ATTEMPTS` times with linear backoff; every user error-isolated.

## 2. Configuration

| Env | Default | Meaning |
|---|---|---|
| `IPPIS_BASE_URL` | `http://ippis-mock:8002` | https in production |
| `IPPIS_AUTH_METHOD` | `api_key` | `api_key` (`X-IPPIS-API-Key: IPPIS_API_KEY`), `oauth2` (`IPPIS_OAUTH_TOKEN_URL/CLIENT_ID/CLIENT_SECRET/SCOPE`), `mtls` (`IPPIS_MTLS_CERT`/`IPPIS_MTLS_KEY`, path or PEM) |
| `IPPIS_CA_BUNDLE` | — | PEM trust for the IPPIS server |
| `IPPIS_SYNC_TIMEOUT` | `5s` | connect + read timeout |
| `IPPIS_SYNC_CONCURRENCY` / `_MAX_ATTEMPTS` / `_PAGE_SIZE` / `_RETRY_BACKOFF` | 10 / 3 / 100 / 500ms | |
| `IPPIS_CLEAR_MISSING` | `false` | |
| `IPPIS_SYNC_SCHEDULE_ENABLED` | `false` | in-process delta + full schedule |
| `IPPIS_SYNC_DELTA_CRON` / `IPPIS_SYNC_FULL_CRON` | `0 */5 * * * *` / `0 30 2 * * *` | Spring cron |
| `IPPIS_WEBHOOK_SECRET` | — | empty = `POST /ippis/events` answers 503 |
| `IPPIS_WEBHOOK_MAX_SKEW` | `300s` | accepted timestamp window |
| `INSTITUTION_REGISTRY_CONFIG` | `configs/institutions.yaml` | shared registry holder |

Secrets accept `<NAME>_FILE` (`IPPIS_API_KEY`, `IPPIS_OAUTH_CLIENT_SECRET`, `IPPIS_MTLS_KEY`,
`IPPIS_WEBHOOK_SECRET`).

## 3. Running it

```bash
# delta (frequent)
java -jar sso-wrapper.jar ippis-reconcile
# full safety net (nightly)
java -jar sso-wrapper.jar ippis-reconcile --full
# what would change, nothing written, watermark untouched
java -jar sso-wrapper.jar ippis-reconcile --full --dry-run
# leavers only
java -jar sso-wrapper.jar ippis-leavers [--dry-run]
```

Options: `--full`, `--dry-run`, `--no-mirror`, `--require-mirror` (use on schedules that exist to
keep the mirror current), `--fail-on-unmapped`, `--max-users=N`, `--no-lock`.

| Exit | Meaning |
|---|---|
| 0 | ok (also: another run holds the lock — skipped) |
| 1 | the run failed (IPPIS unreachable for the delta list, Keycloak down, …) |
| 2 | `--require-mirror` and no usable mirror |
| 3 | `--require-mirror` and mirror writes failed (Keycloak work still done) |
| 4 | `--fail-on-unmapped` and unmapped IPPIS codes seen |
| 64 | usage error |

Alternatively set `IPPIS_SYNC_SCHEDULE_ENABLED=true` on the wrapper (all instances; ShedLock makes one run).

## 4. The push webhook

IPPIS POSTs `{"event": "employee.terminated|employee.updated", "employee": {<flat record>}}` to
`/ippis/events` with:

```
X-IPPIS-Timestamp: <unix seconds>
X-IPPIS-Signature: sha256=<hex HMAC-SHA256(IPPIS_WEBHOOK_SECRET, timestamp + raw body)>
X-IPPIS-Event-Id: <uuid>   (informational)
```

The wrapper checks, in order: secret configured (503) → signature, constant-time, over the exact
bytes (401 `invalid_signature`) → timestamp within `IPPIS_WEBHOOK_MAX_SKEW` (401 `stale_timestamp`)
→ Redis nonce `SET NX` keyed by the signature hash, TTL 2×skew+60 s (409 `replayed`; Redis down =
503, fail closed) → dispatch. `employee.terminated` (or `status: terminated`) offboards; otherwise
re-hire check + attribute sync. Unknown employee → `{"status": "no_user"}`. Every event is audited
`ippis.webhook.event`. The pull reconcile remains the backstop.

## 5. Reading the result
The command prints the status counts, the mirror line, each updated/disabled/error/no_user user, and
WARNING blocks for unmapped codes (fix: add the code to the owning institution's `ippis_codes`),
ambiguous primaries (fix upstream in IPPIS), mirror refusals (reassigned employee number / duplicate
identifier — fix in IPPIS) and mirror failures. `GET /institutions` shows unmapped codes as
`unknown_code` rows.

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| everyone `skipped` | users lack `employee_number` — provision through the directory, or set the attribute |
| `institution` never written | code not in `ippis_codes` (`unmapped_institution`), or ambiguous primary |
| webhook 401 `invalid_signature` | secret mismatch, or the sender signs the body only (the Python reference scheme — `IPPIS_MOCK_SIGNATURE_MODE=body`); the wrapper requires `timestamp + body` |
| webhook 401 `stale_timestamp` | clock skew > `IPPIS_WEBHOOK_MAX_SKEW` — fix NTP |
| webhook 409 `replayed` | the exact same signed request was already accepted |
| "another reconcile holds the lock" | a run is in progress; a killed run's lease expires (≤6 h) |
| a re-hired person stays disabled | they were disabled by hand (`offboarded_by` is a person) — re-enable deliberately |

## 7. Switching from the mock to real IPPIS
Set `IPPIS_BASE_URL` (https), `IPPIS_AUTH_METHOD` and its secrets, `IPPIS_CA_BUNDLE` if the CA is
private; run `ippis-reconcile --full --dry-run` and read the report before the first real run.
