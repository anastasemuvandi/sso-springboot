# Prompt — Rebuild GoRSSO / SSO.RISA on Spring Boot + React

> Use this prompt as-is with a coding agent or an engineering team. It describes
> **everything implemented in the current SSO platform** (FastAPI wrapper + Next.js
> console in front of Keycloak 26.2) and asks for the same system on a
> **Spring Boot backend** and a **React frontend**. Behaviour, security rules and
> API contracts are the specification. The current implementation choices are not.

---

## 0. Your role and the goal

You are a senior engineer building **GoRSSO / SSO.RISA**, the Government of Rwanda's
single sign-on and identity-management platform. Build it with:

- **Backend:** Java 21 and Spring Boot 3.x for every server-side component: the SSO
  wrapper API, the audit service, the notification service, the IPPIS mock, and a
  BFF for the console.
- **Frontend:** React 18 and TypeScript for every user-facing surface: the admin
  console (platform and institution areas), the API explorer, the account-setup
  pages, and the Keycloak login, TOTP and email themes (built with Keycloakify).
- **Identity engine:** Keycloak **26.2 only**, with features
  `organization,admin-fine-grained-authz:v2` and `recovery-codes` set at build time.

Keycloak authenticates people. The Spring Boot **wrapper** sits in front of it. It
exposes typed, RBAC-gated REST endpoints, drives Keycloak through the Admin REST API,
and runs gates that can refuse to hand out a token Keycloak has already minted. Nobody
uses the Keycloak admin console for day-to-day work.

---

## 1. Target technology stack

### Backend (Spring Boot)

| Concern | Use |
|---|---|
| Runtime | Java 21, Spring Boot 3.x, Maven or Gradle multi-module build |
| Web / API | Spring Web MVC, Bean Validation (`jakarta.validation`), `@RestControllerAdvice` for an error envelope |
| AuthN of callers | Spring Security **OAuth2 Resource Server** (JWT, RS256 against the realm JWKS, issuer pinned to `KEYCLOAK_LOCAL_ISSUER/realms/<realm>`, `azp` must be a clientId registered in the realm) |
| AuthZ | Method security (`@PreAuthorize`) plus custom `AuthorizationManager`s for admin tier, institution context and delegated capability |
| Keycloak admin | `org.keycloak:keycloak-admin-client` 26.x behind a **factory that returns a fresh client on every call** (never cache it; a long-lived client keeps a dead refresh token and every later call fails), plus an "as caller" variant that uses the caller's own bearer token |
| Wrapper DB (IPPIS mirror) | PostgreSQL `sso_identity` (never Keycloak's DB), Spring Data JPA, **Flyway** migrations, optional (unset → mirror features answer 503) |
| Cache / state | Spring Data Redis: auth codes, setup-link hashes, import previews, delivery records, rate-limit buckets, overview cache |
| Rate limiting | Bucket4j with a **Redis** backend (shared across instances, configurable, honours `RATE_LIMIT_ENABLED`) |
| Scheduling | `@Scheduled` + **ShedLock** (Redis/JDBC) for the IPPIS reconcile, audit workers and notification detector. Locks have a lease, so a killed run cannot block later runs. |
| HTTP clients | Spring `RestClient`/`WebClient` with Resilience4j retry and timeouts |
| Mail | Spring Mail (`JavaMailSender`) plus Thymeleaf email templates; sending never throws into the caller, and "not configured" is a supported state |
| Excel | Apache POI (signed, protected .xlsx export/import) |
| API contract | springdoc-openapi; the generated `openapi.json` is committed and CI fails when it is stale |
| Observability | Spring Boot Actuator, Micrometer/Prometheus, structured JSON logs with a correlation id |
| Tests | JUnit 5, AssertJ, Mockito, **Testcontainers** (Keycloak 26.2, Postgres, Redis), WireMock for IPPIS/SMTP, ArchUnit for layering rules |

### Frontend (React)

| Concern | Use |
|---|---|
| App | React 18 + TypeScript + Vite |
| Routing | React Router v6 (data routers, route-level guards) |
| Server state | TanStack Query |
| UI | Tailwind CSS + shadcn/ui (Radix), lucide icons |
| Forms | react-hook-form + zod (zod schemas mirror the backend DTOs) |
| Tables | TanStack Table (virtualised for the access matrix) |
| API types | generated from the committed `openapi.json` (`openapi-typescript`) |
| i18n | react-i18next — **English and Kinyarwanda** |
| Login theme | **Keycloakify** (React) → a theme JAR for Keycloak: login, TOTP enrolment/verify, recovery codes, update-password, email templates |
| Session | **BFF pattern**: the SPA never sees a token. A Spring Boot BFF (Spring Security `oauth2Client` + Spring Cloud Gateway `TokenRelay`) holds the tokens server-side behind an HttpOnly, Secure, SameSite cookie, refreshes them, and relays the **caller's own** bearer to the wrapper. The BFF has no service account. |
| Tests | Vitest + React Testing Library, MSW for API stubs, Playwright for the CI gates |

---

## 2. Architecture

```
 Browser (React SPA)  ──cookie──►  console-bff (Spring Boot, token relay)
                                         │ Bearer = caller's own token
 Integrated apps (Spring/React/…) ──────►│
                                         ▼
                              sso-wrapper (Spring Boot)  ──Admin API──►  Keycloak 26.2 ──► Postgres (keycloak DB)
                               │  │   │                                     ▲ gorsso-mfa + audit listener (Java SPI)
                     Redis ◄───┘  │   └──► sso_identity DB (IPPIS mirror, Flyway)
                                  ├──► audit-service (Spring Boot, hash-chained log, Postgres)
                                  ├──► notification-service (Spring Boot, detector + channels)
                                  └──◄ IPPIS (GESB) pull + signed webhook   [ippis-mock in dev]
```

**Layering, enforced with ArchUnit:** `api` (controllers, thin) → `service` (business
logic and every Keycloak call) → `keycloak`/`repository`. **Policy modules are pure Java**
with no Spring and no HTTP, and are unit-tested exhaustively: delegated permissions,
client scope, join key, employee accounts, IPPIS person view, MFA policy, access
matrix, access-matrix sheet, client access decision, setup link, overview, FGAP v2
projection.

---

## 3. Domain model and conventions (keep exactly)

- **Three realms, split by population:** `government-citizens` (public),
  `government-internal` (civil servants, the default realm), `government-admin`
  (platform admins). Realm JSONs are version-controlled. Only `loginTheme` is set;
  the other themes stay at the server defaults.
- **All institutions share `government-internal`** (ADR-0001). An institution
  (ministry/agency) is a **top-level group** keyed by a canonical **lowercase slug**
  (`minagri`, `minisante`). Normalise legacy UPPERCASE groups when reconciling.
  - realm roles are namespaced `inst:<slug>:<role>`
  - per-institution clients are `<slug>-<app>` (e.g. `minagri-kubaka-web`)
  - users carry an `institution` attribute (the slug), sourced from IPPIS
  - Keycloak Organizations are created but **nothing is scoped by them**. Do not add
    Organization-scoped logic.
- **Platform admin tier** = realm role `super_admin` | `admin` | `system_admin`.
- **Institution registry:** `configs/institutions.yaml` (slug, name, IPPIS codes,
  apps). It decides which institutions exist.
- **Delegated permission templates:** `configs/delegated-permissions/templates.json`.
  It decides what local admins may do (see §5.2).
- **Keycloak gotchas:** health and metrics are on management port **9000**; use
  `KC_PROXY_HEADERS=xforwarded` (`KC_PROXY` is gone); `KC_HOSTNAME` is a full URL; the
  bootstrap admin is `KC_BOOTSTRAP_ADMIN_*`; the heap is set with `JAVA_OPTS_KC_HEAP`;
  a group's `subGroups` is always empty, so read `/groups/{id}/children`; the feature
  name is `organization` (singular); sessions are persistent (in the DB); JDK 21;
  `start --optimized` refuses build options at runtime.
- **Server URL ≠ public URL:** `KEYCLOAK_SERVER_URL` (docker hostname) vs
  `KEYCLOAK_PUBLIC_URL`/`KEYCLOAK_LOCAL_ISSUER` (browser-facing).
- **Brute force:** `failureFactor 5`, a flat 900 s lockout. OTP and recovery-code
  failures count. Keycloak answers 403 for a locked account.
- **Password policy:** `length(10) and upperCase(1) and lowerCase(1) and digits(1)`.

---

## 4. Wrapper API: the full surface to implement

Keep the paths, methods and semantics. Platform-admin routes are behind an
**admin-tier gate**. `/institution-admin/*` and `/employees*` are deliberately **not**
behind it; they resolve their own scope.

**Auth and OIDC gateway (public)**
`POST /auth/login`, `POST /auth/logout`, `GET /auth/verify`, `GET /auth/userinfo`,
`GET /oauth2/authorize`, `POST /oauth2/authorize-internal`, `POST /oauth2/token`,
`GET /oauth2/.well-known/openid-configuration`, `GET /oauth2/logout`, `GET /oauth2/jwks`,
`POST /oauth2/introspect`, `GET /saml/metadata`, `GET /health/`, `GET /health/ready`,
public account pages `GET/POST /account/setup`, `GET/POST /account/setup-link`.

**Platform admin**
- Clients: `GET/POST /client/clients`, `GET/PUT/DELETE /client/clients/{id}`,
  `GET /client/clients/{id}/secret`, `POST …/regenerate-secret`, `PUT …/redirect-uris`,
  `PUT …/post-logout-redirect-uris`, `GET/PUT …/scope`, `GET/PUT …/join-column`,
  `GET/PUT …/mfa`
- Users: `GET/POST /users/`, `GET /users/count`, `POST /users/provision`,
  `GET/PATCH /users/{id}`, `PUT /users/{id}/reset-password`, `GET /users/{id}/groups`,
  `PUT/DELETE /users/{id}/groups/{groupId}`, `POST/DELETE /users/{id}/groups/bulk`,
  `GET/DELETE /users/{id}/sessions`, `DELETE /users/{id}/sessions/{sessionId}`
- Agencies (groups): `GET/POST /agency/`, `GET /agency/count`, `GET /agency/metrics`,
  `GET/PUT/DELETE /agency/{groupId}`, `GET/POST …/members`, `DELETE …/members/{userId}`,
  `POST/DELETE …/roles`, `GET/POST …/subgroups`
- Roles: realm roles CRUD, client roles CRUD, user realm/client role mapping,
  `GET /roles/statistics`, `GET /roles/clients`, `GET /roles/client-id/{clientId}/uuid`
- Authorization (per-client access): `GET/PUT /authorization/clients/{clientId}/access`,
  policies and scopes CRUD under `/authorization/clients/{uuid}/…`,
  `GET /authorization/users/{userId}/client-access`,
  `POST /authorization/users/{userId}/client-access/{clientId}`
- Realm: `GET/PUT /realm/config`, `GET/PUT /realm/branding`, `POST /realm/branding/logo`,
  `GET /realm/themes`, `GET/PUT /realm/email/config`, `POST /realm/email/test`,
  `GET/PUT /realm/mfa`, `GET/POST /realm/mfa/exemptions`,
  `DELETE /realm/mfa/exemptions/{userId}`, `GET /realm/overview`
- Dashboard: `GET /dashboard/overview` (cached 60 s, `?refresh=true`), metrics, counts,
  sessions, recent logins, login stats, 24 h activity, events
- Delegated permissions (preview/ops): `GET /delegated-permissions`,
  `GET /delegated-permissions/institutions/{slug}`, `POST …/apply`, `POST …/reload`,
  `GET …/audit`
- Institutions: `GET /institutions` (registry vs Keycloak vs IPPIS reconciliation)
- Audit proxy (read-only; the API key is injected server-side): `GET /audit/events`,
  `GET /audit/export/{csv|pdf}`, `GET /audit/integrity/verify`,
  `GET /audit/retention/policies`
- Lifecycle: `POST /offboarding/terminate`, `POST /access/reassign`,
  `GET /lifecycle/audit[/{userId}]`
- IPPIS push: `POST /ippis/events` (HMAC-signed; see §5.8)
- Admin views: `/admin/agencies/{code}/dashboard|groups`,
  `/admin/clients/{id}/dashboard|users|users/{userId}/roles`,
  `/admin/system/clients|users`. These are mock code today; implement them for real.

**Employee directory (scoped):** `GET /employees`, `GET /employees/{employeeNumber}`

**Delegated institution admin** (`/institution-admin/*`):
`GET /me`, `GET /permissions`, `GET /overview`, `GET /roles`, `GET /audit`,
`GET/POST /users`, `GET/PATCH /users/{id}`, `POST /users/{id}/deactivate`,
`POST/DELETE /users/{id}/roles`, `GET/POST /apps`, `GET /apps/join-columns`,
`GET /apps/{id}/secret`, `GET /apps/{id}/users`, `POST/DELETE /apps/{id}/access`,
`GET/PUT /apps/{id}/join-column`, `PUT /apps/{id}/mfa`,
`POST /access/revoke`, `POST /access/clients-for-users`,
`POST /employees/provision`, `GET /access-matrix`, `POST /grants/bulk`,
`GET /access-matrix/nid/{employeeNumber}`, `POST /access-matrix/setup-email/{employeeNumber}`,
`GET /access-matrix/export`, `POST /access-matrix/import`,
`POST /access-matrix/import/{importId}/apply`.

Error envelope: `{ "error": "<machine_code>", "message": "...", "details": {...} }` with
the codes used throughout this prompt (`access_denied`, `mfa_required`,
`join_key_missing`, `join_key_invalid`, `join_key_not_unique`, `nothing_to_assign`,
`account_ambiguous`, `account_other_institution`, `state_changed`, …).

---

## 5. Functional requirements by feature

### 5.1 Authentication and token gateway
- **Browser flow:** `GET /oauth2/authorize` validates the client, the redirect URI
  (exact match against the client's registered URIs) and PKCE (**S256 only**), then
  302s to Keycloak's auth endpoint, forwarding `state`/`nonce`/`acr_values`. The app
  exchanges the code at `POST /oauth2/token`.
- **Password flow** (`/auth/login`, `/oauth2/authorize-internal`): wrapper-issued
  codes are held in Redis (TTL 300 s, PKCE-bound, **consumed on first use whatever
  the outcome**).
- `refresh_token`, `client_credentials`, discovery, JWKS, introspection and logout.
- **Confidential clients must present their secret** (form body or HTTP Basic). Never
  look a secret up on the caller's behalf.
- **Three gates on every door a user token leaves by:** `/auth/login`,
  `/oauth2/authorize-internal`, and `/oauth2/token` for `authorization_code` **and
  `refresh_token`**.
  1. **Per-client access** (§5.4), 403 `access_denied`, audited `LOGIN_DENIED`
  2. **Join key** (§5.6), 403 `join_key_*`
  3. **Password-grant MFA** (§5.7), 403 `mfa_required` when TOTP applies to that
     client and the person is not `mfa-exempt`
- A bearer without `preferred_username`, or one that fails verification, gets **401**.
  It never skips the gates.
- `/auth/verify` **fails closed**.
- Every login, logout, token and authorize event is **actually written** to the audit
  service (test that it is).
- Security headers, a strict CORS allow-list (startup fails if it contains `*`), and
  Redis-backed rate limits per route.

### 5.2 Delegated (institution / local) administration
- Resolve each caller to an **InstitutionContext** from their `inst:<slug>:*` grant
  role. Every read and write under `/institution-admin/*` carries a **mandatory
  institution filter**. Cross-institution access gets 403 (404 for directory lookups).
  Institution admins hold no Keycloak admin role and may assign only roles in their own
  `inst:<slug>:*` namespace.
- **Templates are configuration, not code.** `templates.json` is written once with a
  `{slug}` placeholder and rendered per institution. Capabilities (Keycloak FGAP
  vocabulary): `view-members`, `manage-members`, `manage-membership`, `map-roles`,
  `view-clients`, `manage-clients`. There are allow and deny patterns for roles and
  clients. **Allows add up across tiers. Denies are absolute.**
- **Guardrail:** an institution admin never holds a broad admin role (the forbidden
  realm roles plus *any* `realm-management` client role). It is enforced at config load
  (an unsafe template is rejected), at request time (403), and by an audit command that
  exits non-zero. A missing or unsafe config makes `/institution-admin/*` **fail
  closed with 503**.
- Ops commands (Spring Boot CLI runner or Gradle tasks): `delegated-validate`,
  `delegated-apply`, `delegated-check`.

### 5.3 Keycloak FGAP v2 (Keycloak holds the boundary too)
- A pure **FGAP v2 projection** compiles `templates.json` into Keycloak permission
  objects. Render it to `configs/generated/fgap-v2-plan.json` (CI fails when stale),
  then apply it with Terraform.
- Emit **one permission per (resource type, scope, tier-set)**, because permissions on
  one object combine UNANIMOUSLY.
- `DELEGATED_EXECUTE_AS_CALLER=true` (the default): delegated calls reach Keycloak
  with the **caller's own token**. Cross-cutting clients' `sso-access-*` policy
  reads/writes use the service account, after the wrapper's scope check.
- Keep an acceptance command (`verify`) that logs in as a real institution admin and
  calls the **Keycloak Admin API directly** (9/9 cases must be refused or allowed
  correctly with no wrapper in the path).

### 5.4 Per-client login access
- `ACCESS_DEFAULT_DENY=true`: a client with no access policy rejects every login at
  token time. Control-plane clients (`sso-admin-ui`/console, `sso-client`) and Keycloak
  built-ins are exempt (`ACCESS_ENFORCEMENT_EXEMPT_CLIENTS`).
- Three Keycloak authorization policies per client, read **by type, not name**:
  `sso-access-groups` (group, positive, direct members only),
  `sso-access-users` (user, positive), and `sso-access-denied-users` (user,
  **negative**, checked **first**, beats every allow).
- `admitUser` adds a named grant and **lifts that person's denial**.
- One pure decision rule is shared by the login gate, the user page
  (states `granted`/`open`/`revoked`/`not_granted`/`exempt`/`unknown`), the overview
  pages and provisioning.
- A malformed policy is skipped, not fatal.

### 5.5 Client scoping (who owns an app)
- Client attributes `sso.client.owner_institution`, `sso.client.cross_cutting` and
  `sso.client.institutions` (`*` = all) resolve to **institution**, **cross-cutting**
  or **platform** (nothing recorded). The owner of an older `<slug>-` client is
  inferred from its prefix.
- A delegated admin creates apps at `POST /institution-admin/apps` with the **app part
  only**. The server composes `<slug>-<app>`, and no institution argument exists.
- Only a platform admin creates cross-cutting clients or edits a scope
  (`PUT /client/clients/{id}/scope`). The edit returns **409 before removing an
  institution that has users on the client**. Once confirmed, it withdraws that
  institution's access.

### 5.6 Join key
- Each client sets `sso.client.join_column` = `nid` | `phone` | `email` (unset = email
  with `explicit:false`). **One** protocol mapper owned by the wrapper (`sso-join-key`)
  emits the claim **`join_key`** under that name whatever the column. `email` is a user
  *property*; `nid`/`phone` are attributes.
- Setting the column writes the attribute and reconciles the mapper in one operation.
  A failed mapper write returns 502.
- Enforce at every token door: 403 `join_key_missing|invalid|not_unique`. Fail
  **open** on an infrastructure error. Skip unset clients unless
  `JOIN_KEY_ENFORCE_UNSET_CLIENTS`.
- Writes of NID, phone and email are validated as usable: 400 malformed, 409 already
  held. The IPPIS reconcile is exempt.
- A change needs `confirm` (409) when the client has live sessions, or when it re-keys
  an already-configured client that has assigned users. A first-time set is not gated.
- `GET /institution-admin/apps/join-columns` is the backfill worklist.

### 5.7 Two-factor authentication (TOTP)
- Realm attribute `mfa_totp_mode` = `off` | `per_client` | `all`. Unset falls back to
  the legacy `mfa_totp_enforced` (unset = `all`), and an unknown value means `all`. In
  `per_client` mode, client attribute `sso.client.mfa_required`: unset or blank =
  required, only `false` opts out. **The same rule exists in the Keycloak Java provider
  and in the wrapper.** Share it as one Java library module used by both.
- Keep the **`gorsso-mfa` Keycloak extension** (Java SPI): a `browser-totp` flow with
  a step-up authenticator (raises the level to 2 for clients that require TOTP, skips
  exempt users), OTP enrol/verify branches, **recovery codes**, a `main` wrapper
  sub-flow, credential removal in `onTopFlowSuccess`, and the `acr.loa.map` 1/2. Also
  keep the **audit event listener** SPI that forwards Keycloak events to the audit
  service.
- Who may change what: super admin sets the mode and any client
  (`PUT /realm/mfa`, `PUT /client/clients/{id}/mfa`). A delegated admin may turn MFA
  **on** for their own `<slug>-*` apps, never off, never on a cross-cutting app. Every
  change is audited with actor, old value, new value and time.
- Exemptions only through `POST /realm/mfa/exemptions` with a reason. Grant clears a
  pending CONFIGURE_TOTP. Bulk CONFIGURE_TOTP runs (idempotent and resumable) only the
  first time the mode becomes `all`, and never for exempt users.
- An installer command applies the flow and policy through the Admin API for offline
  deployments. A test asserts it matches the Terraform flow.

### 5.8 IPPIS identity source
- **Pull reconcile** (scheduled delta/full, ShedLock, watermark) reads IPPIS `/people`
  (person → employments → jobs) and writes the Keycloak attributes `institution`
  (normalised to the registry slug via `ippis_codes`, never raw), `position`,
  `national_id`, `phone`, `ippis_status`, `employee_number`. **Never write the `email`
  property** and **never create an account**. Outcomes: `skipped`, `not_found`,
  `no_user`, `unmapped_institution`, `ambiguous_institution`.
- **Person view policy:** the PRIMARY employment decides `institution` (ties break on
  the earliest start date). If there is genuine ambiguity, leave it unwritten. A
  **leaver has no open post anywhere**, so resigning one of two posts is a move, not an
  exit.
- **Leaver offboarding:** end sessions, disable the account, remove groups, **strip
  `inst:<slug>:*` roles and `sso-access-users` entries**, and audit what was removed.
  **Re-enable re-hires** under an explicit, audited rule.
- **Webhook `POST /ippis/events`:** off (503) unless the secret is set.
  `X-IPPIS-Signature: sha256=<hex HMAC(secret, timestamp + raw body)>` with a
  constant-time compare, reject stale timestamps, and keep a Redis nonce set (replay
  protection).
- Lifecycle endpoints take the **actor from the token**, never from the body.
- **IPPIS mock** (Spring Boot) with ten institutions for dev and tests.

### 5.9 Identity mirror (`sso_identity` database)
- A normalised schema: sector → institution ← employment → job (**append-only,
  enforced by a DB trigger**), employment → employee ← employee_sso_link.
  `employee.id` **is** the IPPIS employee number (natural key).
- The writer **refuses a reassigned employee number** (the same number with a changed
  `national_id`) and counts it as `reassignments`, never written. Mirror failures are
  counted, never raised. `institution.short_code`/`is_onboarded` are INSERT-only.
- **Nothing in the mirror ever decides authorisation.**
- Flyway migrations; a dev seeder that covers every directory state.

### 5.10 Employee directory
- `GET /employees`: one row per **employment** (a dual appointee appears under each
  ministry), with `summary.no_email`.
- Scope: a platform admin sees the whole realm. A local admin sees only their
  institution, with no parameter that changes it.
- `GET /employees/{n}`: filters a scoped caller's `employments` (`scoped:true`),
  returns **404 never 403** for another institution's employee, and is the only place
  `nationalId` is returned.

### 5.11 Employee provisioning (`POST /institution-admin/employees/provision`)
- Select any employees from the mirror, with or without an account, and choose apps.
- **Validation is per (employee, client), never all-or-nothing.** An NID-keyed client
  denies only the people without an NID. An unconfigured client denies everybody.
- Apps already held are **skipped**. Read Keycloak's policies first (deny beats
  allow).
- **Creating an account:** username = the employee's email. The temporary password is
  generated and **never disclosed** (not returned, not logged, not emailed).
  `UPDATE_PASSWORD` is required. The welcome email carries the wrapper's set-password
  link (§5.12) and the app list. "No email" and "no qualifying client" are
  **creation-only** rules.
- **An existing account** is never recreated or altered. Only the apps are granted, and
  the person gets an "access granted" email.
- Resolve accounts for the whole batch first (mirror link → `employee_number`
  attribute → unshared email). Two employees resolving to one account →
  `account_ambiguous`. An account whose `institution` names a different institution →
  `account_other_institution`. Another institution's employee → `not_found`. A client
  outside scope → 403 for the whole request.
- Access is granted **per user** (`admitUser`), never by adding the person to the
  institution's group.
- The report has, per employee: `account` (existing/created/not_created),
  `clients_granted`, `clients_skipped`, `clients_denied`, plus `dry_run`. Every
  assignment is audited per (user, client).
- Dev-only `EMPLOYEE_PROVISION_DEV_PASSWORD` is honoured **only** when debug is on.

### 5.12 Set-password link (account setup)
- The wrapper issues its own single-use link, `/account/setup?token=…`. Only
  `sha256(token)` is stored in Redis. There is **one live link per account** (a new one
  kills the old one). It expires after **72 h** and is spent on a successful set. It is
  dead once `UPDATE_PASSWORD` is no longer pending by any route. GET only checks (mail
  scanners cannot spend it). A password the realm policy refuses does not spend it.
- After the password is set, the page lists the bound apps the person can open **now**
  (through the access rule), each linking to the client's home URL.
- **Resend by the holder** from the sign-in page ("Didn't get the email…"). The answer
  is identical whatever happened, and the page is throttled.
- **Resend by the admin**
  (`POST /institution-admin/access-matrix/setup-email/{n}`, `manage-members`) only when
  the last delivery was `failed`/`not_configured`. 10/min per caller, audited
  `admin.setup_link.resend`.

### 5.13 Access revocation
- `POST /institution-admin/access/revoke`, client-first or user-first. Revoking puts
  the person on `sso-access-denied-users`, which takes one app from one person
  **without** removing them from their institution group. It is batched per client
  (policy writes replace whole policies), previewable, and audited per (user, client).

### 5.14 Access matrix (employees × apps)
- `GET /institution-admin/access-matrix`: rows are the institution's **employees from
  the mirror**, matched to accounts in memory. Locked rows: leavers, accounts outside
  the institution or shared by two employees. **NIDs are masked**; the full NID comes
  only from `/access-matrix/nid/{n}`, audited per call.
- `POST /institution-admin/grants/bulk` (`action: grant|revoke`, items keyed by
  `employeeNumber`, per-item results). A grant to someone with no account goes through
  provisioning under the batch id.
- **Concurrency guard on the server:** grant only adds, revoke only removes. A cell that
  changed since the admin saw it → `rejected`/`state_changed`. Any out-of-institution
  item → 403 for the whole request.
- **Excel round-trip (grant only):** export a protected .xlsx (identity columns locked,
  `YES` dropdowns, every text cell written as a string so formula injection is
  impossible, NIDs not exported, an HMAC signature, institution and date stamped). Import
  → preview (a 30-minute plan in Redis listing every grant and refusal: missing NID or
  email, another institution's file, older than 30 days, bad signature) → confirm
  applies **the stored plan, not the file**. A cleared `YES` is reported, never revoked.
- Grant and revoke emails go to people with existing accounts: **one email per person
  per request**, applied changes only, never on a preview (`NOTIFY_ACCESS_CHANGES`).

### 5.15 Overview pages
- `GET /institution-admin/overview` and `GET /dashboard/overview` have four
  independent sections, each with `available` and `error` (never report a false zero):
  1. **Leavers still holding access** (an enabled account, or a disabled one that still
     reaches an app)
  2. **Waiting for a first password**, bucketed by delivery: failed, not_configured,
     unknown, sent
  3. **Login activity** from Keycloak events over 24 h and 7 days: failures, distinct
     users, the top 5 failing users, **locked out now** from the brute-force detector
     (only users with a recent failure, at most 50), sessions, and `truncated` when
     there are 10,000 events
  4. **Access per application** (named / via group / revoked / sessions). Groups are not
     expanded on the platform side.

### 5.16 Platform administration
- Full CRUD for clients, users, agencies/groups (departments via children), roles,
  realm config, branding/logo, themes, SMTP config and test, sessions, and per-user app
  access.
- Startup **service-account role reconciler**: self-heals `manage-users`,
  `manage-authorization` and the other roles. The admin clients use the `sso-client`
  service account, **not** the master admin.
- The tier check must let `super_admin` through on every platform route. Every declared
  route must be reachable.

### 5.17 Onboarding as code
- Institution registry YAML plus **Terraform** (Keycloak provider) creates the group,
  the `inst:<slug>:*` roles **rendered from the templates**, the `<slug>-<app>` clients,
  the `institution` claim mapper, the `sso-access-groups` policy, the FGAP v2
  permissions, and the MFA flow (`ignore_changes` on the mode). Existing objects are
  **imported**, never recreated. Terraform does not manage the control-plane clients,
  users or person-role assignments.
- Commands: `registry-validate`, `tf-validate`, `tf-plan`, `tf-apply`,
  `fgap-plan(-check)`.

### 5.18 Audit service (Spring Boot)
- Append-only, **SHA-256 hash-chained** events (`hash_version`, `institution`,
  `target`, actor, category, outcome, ip, details). Integrity verification endpoint,
  retention policies and **legal hold**, CSV/PDF export, API-key auth.
- Three writers: the wrapper, the Keycloak listener, and its own scheduled workers
  (health check, certificate expiry, access review).
- Read paths: the platform proxy, the **institution-scoped** view
  (`/institution-admin/audit`), and the lifecycle proxy.

### 5.19 Notification service (Spring Boot)
- Detector over the audit trail (scheduled):
  - `brute_force`: ≥5 failures per user or IP in 10 min, CRITICAL
  - `unusual_hours`: outside 06:00–22:00 UTC+2
  - `impossible_travel`: 2+ IPs in 30 min
  - `anomalous_access`: first login for a (user, app) pair
- Channels: email, in-app and Slack-style webhook. A skipped channel is recorded as
  skipped. Per-rule cooldowns, config overrides (`GET/PUT /config`), alerts
  (list/get/acknowledge), notifications (list/read/read-all), daily report.
- Must actually fire end-to-end (integration test from audit event → alert).
- Separate from the **wrapper's own mailer** (welcome, setup link, access
  granted/revoked).

### 5.20 SDKs and integration
- Generate client SDKs from `openapi.json` (Java and TypeScript at minimum).
- Provide a **Spring Boot starter** for integrated apps (resource-server config, a
  `join_key` claim resolver, an `inst:<slug>:*` role mapper) and a **React SDK**
  (PKCE login through the wrapper, a BFF-friendly session hook, route guards).
- Ship sample apps (a Spring Boot API plus a React SPA) for two demo ministries
  (`minagri`, `minisante`).

---

## 6. Frontend (React): pages to build

**Console shell:** login via the BFF (PKCE through the wrapper's `/oauth2/*`), a
role-aware layout. Platform admins land on `/dashboard`, institution admins on
`/institution`. The console **decides nothing**: hiding a button is cosmetic, and the
wrapper enforces every rule.

**Platform area**
- `/dashboard`: the overview widgets (cards, quick actions, needs-attention list,
  recent activity)
- `/clients`, `/clients/new`, `/clients/:id` (redirect URIs, secret reveal/regenerate,
  scope editor with the 409 confirmation, join column, MFA, access policies),
  `/clients/join-keys` (backfill)
- `/users`, `/users/new`, `/users/:id` (profile, groups, roles, sessions, app access
  with grant/revoke/restore, reset password, MFA exemption)
- `/agencies`, `/agencies/new`, `/agencies/:id` (members, roles, departments)
- `/roles`, `/employees` (realm-wide directory with a detail drawer), `/audit`,
  `/system-audit` (integrity verification, export), `/settings` (realm config,
  branding/logo, email/SMTP test, MFA mode and exemptions)

**Institution area** (`/institution/*`)
- `/institution`: overview
- `/institution/staff`, `staff/new`, `staff/:id`, `staff/:id/roles`
- `/institution/roles`
- `/institution/apps`, `apps/new` (app part only, with the slug prefix shown and
  fixed), `apps/:clientId/users`, `apps/:clientId/join-key`, `apps/join-keys`
- `/institution/employees`, `employees/provision` (multi-select across pages, dry run,
  per-employee report with the four facts)
- `/institution/access/matrix`: a virtualised grid with pending-change state (a pure,
  tested reducer), bulk grant/revoke, masked NID reveal, resend setup email, export
  to Excel
- `/institution/access/matrix/import`: upload → preview → confirm → results
- `/institution/access/revoke`: client-first and user-first, with preview
- `/institution/audit`

**Other React surfaces**
- `/api-explorer`: Swagger UI or Redoc over `openapi.json`
- `/account/setup` and `/account/setup-link`: public pages for the set-password and
  resend flows
- **Keycloakify login theme** (`government-citizens` and `gor-admin` variants):
  Government branding, the "Didn't get the email…" link (shown when
  `GORSSO_ACCOUNT_SETUP_URL` is set), TOTP enrolment/verify/recovery-code pages, and
  English plus Kinyarwanda

Frontend quality gates (CI): `tsc --noEmit`, `vite build`, lint, Vitest unit tests for
the pure state modules (access-matrix state, employee selection, client-scope form,
MFA status), and Playwright gates that run the built console against a stubbed wrapper
(auth-guard/refresh gate, employee-selection gate).

---

## 7. Security invariants (each needs a regression test)

1. The institution boundary is held **twice**: by the wrapper (mandatory filter) and by
   Keycloak (FGAP v2, as caller). The CI **isolation** job is not path-filtered or
   env-gated, and fails if a required scenario class collects zero tests.
2. Denies beat allows, both for delegated templates and for client access.
3. No token leaves without the access, join-key and MFA gates, including on refresh.
4. Confidential clients always authenticate. Auth codes are single-use.
5. Temporary passwords are never disclosed. Setup links are stored hashed and are
   single-use.
6. Excel import applies only the server-side preview plan, re-checks everything, and
   never revokes.
7. The actor in the audit trail always comes from the authenticated principal.
8. Login, token and admin events always reach the audit trail.
9. Offboarding removes roles and named grants. Webhooks have replay protection.
10. No secrets in logs. Secrets come from files or env (`*_FILE` support), and
    staging/prod require every secret (startup fails on a missing secret).

---

## 8. Infrastructure, CI and operations

- **Docker Compose:** a base (Keycloak 26.2 + Postgres), apps (wrapper, BFF/console,
  audit, notification, IPPIS mock, Redis, MailHog, Adminer), an MFA overlay, and
  staging/prod overlays (`!override` on Keycloak's environment, `${VAR:?}` for every
  secret). Keycloak image built from `keycloak/Dockerfile` with the providers and the
  Keycloakify theme JAR copied in before `kc.sh build`.
- **GitLab CI (`.gitlab-ci.yml`)**, in stage order: `isolation`, `config-safety`
  (templates + registry), `terraform-validate`, `unit-tests` (coverage floor ≥ 60%),
  `openapi-drift`, `fgap-plan-check`, `console-gate`, `terraform-plan/apply` (dev
  runner), `delegated-check`. Images are built in CI and pushed to a registry.
- **Runbooks:** onboard an institution, enable MFA, IPPIS sync, create the
  `sso_identity` DB, local database access, production sizing (3 VMs), TLS.
- Health and readiness via Actuator. Keycloak is probed on `:9000`.

---

## 9. Deliverables and order of work

1. Multi-module skeleton (`sso-wrapper`, `console-bff`, `audit-service`,
   `notification-service`, `ippis-mock`, `mfa-policy` shared lib, `keycloak-extensions`,
   `console-web` React app, `keycloak-theme` Keycloakify app), compose stack, CI
   skeleton.
2. Pure policy modules with exhaustive unit tests (§2), ported rule-for-rule.
3. Auth gateway and the three gates; the audit emit path.
4. Platform-admin API plus the platform console area.
5. Delegated admin plus FGAP v2 projection, Terraform and the isolation suite.
6. IPPIS client, mirror (Flyway), reconcile, leavers and webhook; the employee
   directory.
7. Client scoping, join key, provisioning, setup link, revocation, access matrix and
   Excel round-trip, plus the institution console area.
8. MFA (shared policy, Keycloak SPI, Keycloakify pages, admin endpoints).
9. Overview pages, the notification service, SDKs, sample apps, runbooks.

For each step: code, tests (unit + Testcontainers integration), updated `openapi.json`,
and a short `docs/implemented/<feature>.md` naming the code locations, endpoints,
console pages and tests.

**Definition of done:** every endpoint in §4 exists with the semantics in §5. Every
invariant in §7 has a passing test. The isolation suite and the Keycloak-direct
`verify` (9/9) pass against a Keycloak 26.2 Testcontainer. The console builds and
passes its gates. `docker compose up` brings up the full stack with seeded `minagri`
and `minisante`.
