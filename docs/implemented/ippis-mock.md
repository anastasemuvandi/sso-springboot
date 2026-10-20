# ippis-mock: simulated IPPIS (GESB) HR feed (spec §5.8)

This module ports the reference `ippis-mock/app/main.py` (FastAPI) to Spring Boot 3.5 / Java 21. It is for dev and tests only, and every person in it is invented. It keeps the in-memory store, and JSON-file persistence is optional. It listens on port **8002** inside the container, as the reference does, and compose publishes it on **8003**.

## Code map (`ippis-mock/`, package `rw.gov.risa.sso.ippismock`)

| Path | What |
|---|---|
| `api/IppisController` | The read contract plus the mock control plane (all endpoints below). |
| `api/ApiKeyInterceptor` | `X-IPPIS-API-Key` must equal `IPPIS_API_KEY` (constant-time). Applies to `/employees**`, `/people**` and `/_mock/**`; otherwise 401 `invalid_api_key`. |
| `api/ErrorHandler`, `ApiException` | Error envelope `{"error","message","details"}`. |
| `api/Timestamps` | Parses `changed_since` the way `datetime.fromisoformat` does (offset, `Z`, naive = UTC, bare date). It repairs a `+` that arrived as a space. `now()` uses Python's `isoformat()` shape. |
| `store/SeedData` | The seeded roster (see below), plus `EMPLOYERS`/`SECTORS` (code → name, sector). |
| `store/StoredPerson`, `Employment`, `Job` | The store model. `employments == null` marks a flat record from the reference. Otherwise the person has real employments. |
| `store/Projections` | The two wire shapes. `flat()` → `/employees`. `person()` → `/people`, which synthesises the reference's one employment and one job (`<number>-j1`, `startDate 2020-01-01`, closed on `updated_at[:10]` when terminated). |
| `policy/PersonView` | Pure Java copy of the wrapper's person-view rules: primary employment, ties on start date mean ambiguous, and a leaver has no open job anywhere. It derives the flat record of a multi-employment person. |
| `store/EmployeeStore` | Store kept in insertion (seed) order. Optional atomic JSON persistence via `IPPIS_MOCK_DATA_FILE`. |
| `webhook/WebhookEmitter` | Opt-in signed push to the wrapper's `POST /ippis/events`. |
| `config/*` | Properties, `*_FILE` secrets (EnvironmentPostProcessor), and `X-Correlation-Id` → MDC. JSON logs use the logstash encoder. |
| `Dockerfile` (+ `Dockerfile.dockerignore`) | Multi-stage build (maven → `eclipse-temurin:21-jre-alpine`), non-root, healthcheck on `/health`. Build it from the repo root. |

## Endpoints

The reference has no pagination. Without `limit`/`offset` a response is byte-for-byte the reference's shape.

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | No key. `{"status":"ok","service":"ippis-mock"}`. |
| GET | `/employees[?changed_since=&limit=&offset=]` | `{"employees":[flat...],"total":n}`. A bad date returns 400 `invalid_changed_since`. |
| GET | `/employees/{number}` | Flat record, or 404 `not_found`. |
| GET | `/people[?changed_since=&limit=&offset=]` | `{"people":[nested...],"total":n}`. This is the person → employments → jobs feed (camelCase). |
| GET | `/people/{number}` | Nested person, or 404. |
| POST | `/employees` | **Control plane.** Enroll: 201, 409 `conflict`, or 422 when `employee_number` is missing. Unknown fields are ignored. |
| PATCH | `/employees/{number}` | Updates only the fields provided. `employee_number` is immutable. On a multi-employment person, `status: terminated` closes every open job. Any other institution/position/status change collapses the person to a flat record. |
| DELETE | `/employees/{number}` | Soft terminate: the record is still served. On a multi-employment person it closes every open job. |
| PUT | `/people/{number}` | **Extension.** Upserts a nested person (201/200), so you can create dual appointments and movers at runtime. Employer name and sector are filled from the registry. |
| POST | `/_mock/reset` | **Extension.** Restores the seeded roster. |

Pagination (an extension): `limit` is 1..1000 and `offset` is ≥ 0. Order is stable (seed order). When you paginate, the response also carries `offset` and `limit`, and `total` counts all matching records.

Every write stamps `updated_at = now`, so a delta (`changed_since`) picks it up.

## Seed data: ten employers

- **The reference roster, byte-for-byte**: EMP001–003, EMP404, EMP1001–1015, EMP2010, EMP2101, EMP3101, EMP5001–5014 and EMP4001. It covers these cases:
  - missing phone (EMP002, EMP1007), missing NID (EMP1008), missing both (EMP1009)
  - no email (EMP1010) and a 15-digit NID (EMP1011)
  - one email shared by two people (EMP1012/1013)
  - suspended (EMP003, EMP1014) and terminated (EMP404, EMP1015)
  - national-form phones
  - **MOH** (EMP4001), the tenth employer, which is deliberately unmapped in `ippis_codes`
- **Multi-employment people**, which the reference's flat store could not express:
  - EMP2008 — **leaver** (every job closed). From the reference `seed_identity_mirror_dev.py`, same identifiers.
  - EMP2009 — **dual appointee**: MINAGRI primary, MINISANTE secondary. Same source.
  - EMP6001 — **mover**: resigned the primary MINEDUC post and still holds a secondary MINAGRI post. Status `active`, institution `MINAGRI`.
  - EMP6002 — **ambiguous primary**: two primary posts (MINIJUST and RDB) starting the same day. The flat record has no `institution`.
  - EMP6003 — two primary posts; the earliest one held (MININFRA) wins.

## Webhook emission (opt-in)

Emission is on only when both `SSO_EVENTS_URL` and `IPPIS_WEBHOOK_SECRET` are set. Each write POSTs `{"event":"employee.updated|employee.terminated","employee":<flat record>}` with these headers:

- `X-IPPIS-Timestamp: <unix seconds>`
- `X-IPPIS-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + raw body)>` (SPEC §5.8)
- `X-IPPIS-Event-Id: <uuid>`

Emission is best-effort: a failed delivery is logged and never fails the write. `IPPIS_MOCK_SIGNATURE_MODE=body` signs the raw body only, which is the Python reference wrapper's scheme.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8002` | HTTP port. |
| `IPPIS_API_KEY` (`_FILE`) | `change-me-in-ippis` | Shared key. Must match the wrapper's `IPPIS_API_KEY`. |
| `IPPIS_MOCK_DATA_FILE` | (empty = in-memory) | JSON persistence. |
| `SSO_EVENTS_URL`, `IPPIS_WEBHOOK_SECRET` (`_FILE`) | empty | Turn on push emission. |
| `IPPIS_MOCK_WEBHOOK_TIMEOUT` | `5s` | Webhook timeout. |
| `IPPIS_MOCK_SIGNATURE_MODE` | `timestamped` | `timestamped` or `body`. |

## Tests (`mvn -q -B -f ippis-mock/pom.xml verify`): 45 tests

- `IppisMockApiTest` (MockMvc, 36 tests) ports every reference test:
  - health, key auth, 404, delta (`changed_since`) plus its 400
  - enroll/409/422, patch, terminate
  - nested shape, stable job id, closed job for a leaver, open job for a suspension
  - absent rather than null, every employer named, ten employers

  It also covers the multi-employment cases, PUT, pagination and reset.
- `WebhookEmissionTest` (WireMock) checks the signature over timestamp + body, the event types and unique event ids, and that a 500 or a dead endpoint never fails the write.
- `EmployeeStorePersistenceTest` checks persistence across restart (including nested people) and that a corrupt file is reseeded.
- `PersonViewTest` covers the primary, tie, leaver and empty cases.

## Differences from the reference

- Enrolled records omit null fields rather than echoing `null`.
- Errors use the platform envelope instead of FastAPI's `{"detail": ...}`.
- A naive `changed_since` is read as UTC. The reference returned 500 on it, because it compared a naive value with an aware one.
