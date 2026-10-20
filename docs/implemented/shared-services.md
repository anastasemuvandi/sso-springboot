# Shared services (`sso-wrapper`, package `rw.gov.risa.sso.wrapper.service.*`)

The service layer every wrapper feature builds on. Read `wrapper-core.md` first. Everything here is a
Spring bean (constructor-inject it). Rules live in the pure `policy.*` modules — these services only
talk to Keycloak / Redis / SMTP and call those rules. **Public signatures below are stable.**

Conventions used throughout:

- Failures are `error.ApiException` (status + machine `error` code + message + `details`). Keycloak
  Admin API failures that are not caught propagate as `WebApplicationException` (the global handler maps them).
- Writes in these services are **not** audited unless stated; the calling feature audits with its own
  context (actor from `CallerPrincipal`, institution, batch id, …).
- Read models are records annotated `@JsonNaming(SnakeCase)` so a controller may return them directly.

---

## 1. `service.keycloak` — who executes, and small Admin API helpers

### `KeycloakExec` (interface)
`<T> T call(Function<RealmResource,T> work)`, `void run(Consumer<RealmResource>)`, `boolean asCaller()`,
`String label()`; `static KeycloakExec of(RealmResource realm[, boolean asCaller])` (tests).
Every `call` builds a **fresh** admin client and closes it — group several Admin API calls in one lambda.

### `KeycloakExecutors` (bean)
- `KeycloakExec serviceAccount()` — the `sso-client` service account.
- `KeycloakExec asCaller(String accessToken)` / `asCaller(CallerPrincipal)` — the caller's own token; never falls back.

### `KeycloakDirectory` (bean) — all methods take the `RealmResource` of an open unit of work
| Method | Semantics |
|---|---|
| `Optional<ClientRepresentation> findClient(realm, clientId)` / `requireClient(...)` (404 `not_found`) | exact clientId |
| `List<ClientRepresentation> listClients(realm)` | paged (100) |
| `Optional<UserRepresentation> getUser(realm, id)` | full rep incl. attributes; 404 → empty |
| `Optional<UserRepresentation> findUserByUsername(realm, username)` | exact |
| `List<UserRepresentation> findUsersByEmail(realm, email, max)` | exact, case-insensitive |
| `List<UserRepresentation> findUsersByAttribute(realm, name, value, max)` | `q=name:value&exact=true`, full reps |
| `List<UserRepresentation> listUsers(realm, first, max, full)`; `forEachUserPage(realm, pageSize, full, Predicate<List<UserRepresentation>>)` | paging (return false to stop) |
| `List<GroupRepresentation> userGroups(realm, userId)` | DIRECT groups (what the access rule reads) |
| `credentials(realm, userId)`, `credentialTypes(realm, userId)` (lower-case) | |
| `Optional<GroupRepresentation> groupByPath(realm, path)`, `getGroup(realm, id)` | |
| `List<GroupRepresentation> groupChildren(realm, groupId)` | via `/groups/{id}/children` (**`subGroups` is always empty on KC26**) |
| `List<String> groupTreeIds(realm, rootId)` | root + all descendants, BFS |
| `List<UserRepresentation> groupMembers(realm, groupId, max)` | direct members, full reps |
| `long activeSessionCount(realm, clientUuid)` | online + offline sessions |
| `realmRole`, `ensureRealmRole(realm, name, description)`, `realmRoleUsers(realm, role)` (direct holders, paged; missing role → empty), `userRealmRoles`, `addRealmRoles(realm, userId, names)`, `removeRealmRoles(...)` | unknown names skipped |
| `static String firstAttribute(UserRepresentation, name)` | trimmed or null |

---

## 2. `service.clientaccess.ClientAccessService` — per-client login access (spec §5.4)

Three Keycloak authorization policies per client: `sso-access-groups` (group, POSITIVE, direct members),
`sso-access-users` (user, POSITIVE), `sso-access-denied-users` (user, **NEGATIVE**, checked first). Reads
are **by type** (every user/group policy counts; malformed config contributes nothing); writes target the
canonical names (`policy.clientaccess.CanonicalPolicies`). Authorization services are enabled on the client
on first write (public client → 409). Every write = **one** policy read + at most one write per policy,
serialised per client in this JVM. No cache (the reference has none).

**Who executes.** Methods touching a client's policies take a `KeycloakExec`. The clientId→uuid lookup
always uses the service account (catalogue query FGAP v2 cannot express). For a **cross-cutting** client
always use `execFor(exec, true)` (= service account) — and only after your own scope check.

Config: `sso.access.default-deny` (`ACCESS_DEFAULT_DENY`, default true); exempt clients are core
`sso.keycloak.exempt-clients` (`ACCESS_ENFORCEMENT_EXEMPT_CLIENTS`).

| Method | Semantics |
|---|---|
| `KeycloakExec serviceAccount()`; `KeycloakExec execFor(KeycloakExec requested, boolean crossCutting)` | |
| `boolean isExempt(clientId)`, `boolean defaultDeny()`, `List<String> exemptClients()` | |
| `ClientRepresentation client(clientId)` | SA lookup, 404 |
| `List<AccessPolicy> policies(exec, clientId)` | all policies, adapted |
| `ClientAssignment assignment(exec, clientId)` | users / groups / deniedUserIds |
| `List<String> deniedUserIds(exec, clientId)` | |
| `AssignmentView describeAssignment(exec, clientId)` | `{client_id, enforced, users[{id,name}], groups[{id,name}], denied_user_ids}` (unresolvable name → null) |
| `AccessDecision decide(exec, userId, directGroupIds, ClientRepresentation)` | the shared rule; exempt client reads nothing |
| `Map<String,AccessDecision> decideForUsers(exec, clientId, Map<userId, directGroupIds>)` | many users, ONE client, one read |
| `Map<String,AccessDecision> decideForUserIds(exec, clientId, userIds)` | same, reads each user's groups first |
| `UserAccessView userClientAccess(exec, userId)` | one user × every client except Keycloak built-ins; items `{client_id, client_uuid, name, state: granted\|open\|revoked\|not_granted\|exempt\|unknown, has_access, reason, direct, via_groups, denied, denied_by_name, denied_groups, error}`; unreadable client → `unknown` + `error`; 404 for no user |
| `static AccessItem accessItem(ClientRepresentation, AccessDecision, Map<groupId,name>)` | |
| `LoginCheck checkLogin(username, clientId)` | the gate (SA): `{has_access, reason, exempt, user_groups, required_groups, message}`; refusals never throw; only "cannot work" throws (503) |
| `LoginCheck enforceLogin(username, clientId, requestIp, grantType)` | on refusal: audits `LOGIN_DENIED` and throws 403 `access_denied` |
| `AssignmentView setAssignments(exec, clientId, userIds, groupIds)` | replace allow lists; empty deletes policy; named users' denials lifted |
| `AdmitResult admitUser(exec, clientId, userId)` | add named grant **and lift that person's denial**; idempotent (`admitted`, `liftedDenial`, `changed()`) |
| `AdmitManyResult admitUsers(exec, clientId, userIds)` | one write (`admitted`, `already`, `lifted`) |
| `ClientAssignment grantGroup(exec, clientId, groupId)` / `ungrantGroup(...)` | additive / removes only that group |
| `DenyResult denyUsers(exec, clientId, userIds)` | add to deny-list (`changedUserIds` = new) |
| `DenyResult restore(exec, clientId, userIds)` | lift deny entries (`changedUserIds` = lifted) |
| `RevokeResult revokeUsers(exec, clientId, List<RevokeTarget(userId, directGroupIds)>)` | ONE pass per client: named grant removed; via-group → deny entry; both if both (`unassigned`, `denied`, `alreadyDenied`, `notAssigned`) |
| `RevokeResult revokeUserIds(exec, clientId, userIds)` | same, reads groups itself |
| `WithdrawResult removeInstitutionAccess(exec, clientId, slug, groupId /*nullable*/)` | removes that institution's group (by id or case-insensitive name) + named users whose `institution` attribute is the slug; nobody else |
| `Map<String,InstitutionAccess> institutionAccessSummary(exec, clientId, slugs)` | per slug `{institution, groupAssigned, users}` (zeroes present) |
| `GrantRemoval removeNamedGrantsEverywhere(userId)` | leaver offboarding (SA): removes the `sso-access-users` entry on every non-exempt client; `{user_id, changed_client_ids, failures{clientId: reason}}`; deny entries kept |

Result types: `policy.clientaccess.*` (`AccessDecision`, `ClientAssignment`, `ClientAccessWrites.*Result`)
and `service.clientaccess.ClientAccessViews.*`.

---

## 3. `service.institution` — delegated administration plumbing (spec §5.2)

Config (`sso.delegated.*`): `permissions-config` (`DELEGATED_PERMISSIONS_CONFIG`), `institution-registry-config`
(`INSTITUTION_REGISTRY_CONFIG`) — relative paths resolve against the working dir, then its parent (repo root),
then `/app`; `guardrail-enforce` (`DELEGATED_GUARDRAIL_ENFORCE`), `claim-crosscheck` (`DELEGATED_CLAIM_CROSSCHECK`),
`institution-claim` (`DELEGATED_INSTITUTION_CLAIM`), `execute-as-caller` (`DELEGATED_EXECUTE_AS_CALLER`, default true).

- **`DelegatedPermissionsHolder`**: loads `templates.json` at startup. `DelegatedPermissionRegistry current()`
  (throws `DelegatedPermissionConfigException` when missing/unsafe; retried at most every 5 s), `boolean available()`,
  `Optional<String> unavailableReason()`, `Path source()`, `GrantLookup grantLookup()`, `DelegatedPermissionRegistry reload()`
  (parse first, swap on success; throws and keeps the old one otherwise). The app always boots.
- **`InstitutionRegistryHolder`**: `InstitutionRegistry current()`, `Set<String> knownSlugs()`, `RegistryFindings findings()`,
  `reload()`. Never fails closed (missing → empty registry); validator findings are logged.
- **Fail closed**: an interceptor answers **503 `delegation_unavailable`** on every `/institution-admin/**`
  request while the templates are unavailable.
- **`InstitutionContextService`**:
  - `InstitutionContext resolve(CallerPrincipal, String requestedInstitution /*nullable*/)` and
    `resolve(caller, requested, Capability... required)` — refusals become `ApiException` with the policy's
    status and code (`not_institution_admin`, `institution_not_administered`, `institution_required`,
    `multiple_institutions`, `unknown_institution`, `guardrail_violation`, `institution_claim_mismatch`,
    `capability_denied`, …; 503 → `delegation_unavailable`). A guardrail violation is audited
    `admin.delegated.guardrail_violation` (outcome `denied`, or `warning` when not enforced).
  - `DirectoryScope resolveDirectoryScope(caller, requested)` (employee directory).
  - `static void requireCapability(InstitutionContext, Capability)` → 403 `capability_denied`.
  - `static ApiException toApiException(Refusal | InstitutionRefusedException)`; `CallerIdentity identity(CallerPrincipal)`.
- **`@CurrentInstitution`** controller parameter:
  ```java
  @GetMapping("/institution-admin/apps")
  List<AppDto> apps(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx) { … }
  ```
  Reads `?institution=` (attribute `param`). Resolved or refused before your method runs.
  `InstitutionRefusedException` thrown by the policy record's own `ctx.assert*(..)` is mapped to the envelope too.
- **`DelegatedExecution`**: `KeycloakExec forCaller(CallerPrincipal)` (as caller when the flag is on and a
  token is present, else SA), `current()`, `forClientPolicies(caller, boolean crossCutting)` (SA when
  cross-cutting), `serviceAccount()`, `<T> T run(caller, Function<RealmResource,T>)`, `executeAsCaller()`.
  Always SA regardless of the flag: catalogue queries, client creation, client attribute writes (scope,
  join column, MFA), cross-cutting `sso-access-*` policies.

---

## 4. `service.clientscope.ClientScopeService` — who owns a client (spec §5.5)

Attributes `sso.client.owner_institution`, `sso.client.cross_cutting`, `sso.client.institutions` (rules:
`policy.clientscope`). All Keycloak work uses the service account.

| Method | Semantics |
|---|---|
| `Set<String> knownSlugs()` | registry slugs |
| `ClientScope scopeOf(ClientRepresentation)` | owner inferred from `<slug>-` prefix when unrecorded |
| `ClientScope getScope(clientId)` (404) / `ClientScope appScope(clientId)` (404 "does not exist") | |
| `ScopeState scopeState(clientId)` | `GET …/scope`: `{scope, access[{institution, group_assigned, users}]}` for covered institutions |
| `List<VisibleClient> clientsVisibleTo(InstitutionContext)` | own `<slug>-*` (per template) + cross-cutting covering it; `VisibleClient(client, scope, shared, manageable, owned)` |
| `ClientScope requested(clientId, crossCutting, institutions, owner)` | validate; 400 `invalid_request` |
| `ClientScope writeScope(clientId, ClientScope)` | RMW of attributes (all keys written); 400 on failure |
| `ScopeChangePolicy.Plan planChange(clientId, before, after, confirm)` | no writes |
| `ScopeChange changeScope(clientId, after, confirm, actor, requestIp)` | **409 `confirm_required`** (details: `message, removed_institutions, access_at_risk[], current_scope, requested_scope`) before removing an institution that has users on the client; with confirm: write + `removeInstitutionAccess` per removed institution; audited `admin.client.scope_changed` (+ one `scope_institution_removed` per institution). Returns `{scope, previous, removed_institutions, access_withdrawn[]}` |

---

## 5. `service.joinkey.JoinKeyService` — join key (spec §5.6)

Config (`sso.join-key.*`): `enforce` (`JOIN_KEY_ENFORCE`, true), `enforce-unset-clients`
(`JOIN_KEY_ENFORCE_UNSET_CLIENTS`, false), `validate-format` (`JOIN_KEY_VALIDATE_FORMAT`, true),
`readiness-limit` (`JOIN_KEY_READINESS_LIMIT`, 200). Service account throughout.

| Method | Semantics |
|---|---|
| `JoinColumnSetting getSetting(clientId)`; `static settingOf(ClientRepresentation)` | unset = email, `explicit=false` |
| `int activeSessions(clientId)` | -1 when unreadable |
| `JoinColumnChange setJoinColumn(clientId, JoinColumn, actor, confirm)` | writes `sso.client.join_column` (+changed_by/at) AND reconciles the single `sso-join-key` mapper (claim `join_key`; email = property mapper, nid/phone = attribute mapper). 400 non-OIDC / attribute write failure; **409 `confirm_required`** (details `message, active_sessions, assigned_users, assigned_users_is_floor, current, requested`) when sessions are live or an already-configured client with assigned users is re-keyed (first-time set not gated on users); **502 `keycloak_error`** when the mapper write fails. `describe()` → `{setting, previous, active_sessions, assigned_users, mapper: created\|updated\|unchanged, readiness}`. Not audited (caller audits `admin.client.join_column_changed`) |
| `MapperReconciler.Kind reconcileMapper(realm, clientUuid, setting)` | idempotent, by name |
| `AssignedPopulation assignedPopulation(clientId)` | named users + assigned groups' members, capped (`truncated`) |
| `Readiness readiness(clientId, JoinColumn /*null=current*/)` | `describe()` → `{join_column, checked, blocked, truncated, users[{user_id, username, error, detail}]}` |
| `JoinKeyResolution resolveForUser(realm, user, setting)` | uniqueness search only for a well-formed value |
| `Optional<JoinKeyCheck> checkLogin(username, clientId)` | empty when the gate does not apply (no username / enforce off / exempt / unset client unless enforce-unset) **or on any infrastructure error (fail open)** |
| `Optional<JoinKeyCheck> enforceLogin(username, clientId, requestIp, grantType)` | refusal: audits `LOGIN_DENIED`, throws **403 `join_key_missing\|join_key_invalid\|join_key_not_unique`** with details `{join_column, claim, user_attribute}` |
| `void validateIdentifierWrite(boolean emailPresent, String email, Map<String,? extends Collection<String>> attributes, String selfUserId)` / `validateIdentifierWrite(UserRepresentation body, selfUserId)` | NID (`national_id`), `phone`, `email`: **400 `identifier_malformed`** / **409 `identifier_taken`** (details `{field, column}`); search failure is skipped. Never for the IPPIS reconcile |
| `Map<String,Object> backfillWorklist(InstitutionContext)` | `GET /institution-admin/apps/join-columns` body (`institution, choices, total, configured, unconfigured, outstanding, needs_platform_admin, malformed, items[]`); 403 without `view-clients` |

---

## 6. `service.mfa` — TOTP (spec §5.7)

The rule is the shared `mfa-policy` module (`MfaPolicy`, `SwitchState`, `MfaConstants`). Service account
(needs `manage-realm`, `manage-clients`, `manage-users`).

**`MfaService`**
| Method | Semantics |
|---|---|
| `Map<String,Object> getState()` | `{mode, raw_mode, enforced, effective, raw, browser_flow, flow_ready, first_enabled_at, exempt_count}` |
| `SwitchState currentState()` | cached 5 s |
| `ModeChange setMode(mode, actor, ip)` / `setEnforced(boolean, actor, ip)` | 400 bad mode; 409 turning on while browser flow ≠ `browser-totp`; audited `admin.mfa.switch_changed` (old/new/time, also when unchanged); first time mode becomes `all` sets `mfa_totp_first_enabled_at` and starts bulk enrolment (`bulk_enrol: {started, reason}`) |
| `ClientRequirement clientRequirement(clientId)` | `{client_id, required, raw, mode, applies_now}` |
| `ClientRequirementChange setClientRequirement(clientId, required, actor, ip, institution /*nullable*/)` | writes `sso.client.mfa_required`; audited `admin.mfa.client_requirement_changed` |
| `static void refuseDelegatedChange(boolean required)` | 403 for OFF (delegated may only turn ON; you also refuse cross-cutting) |
| `List<Exemption> listExemptions()` | `{user_id, username, reason, granted_by, granted_at}` |
| `Exemption grantExemption(userId, reason, actor, ip)` | 400 reason <15/>500 chars; 409 no `mfa-exempt` role; 404 user; attributes declared admin-only in the user profile; **clears pending CONFIGURE_TOTP**; audited `admin.mfa.exempt_granted` |
| `Map revokeExemption(userId, actor, ip)` | audited `admin.mfa.exempt_revoked` |
| `static void refuseExemptRole(Collection<String> roleNames, String where)` | 400 — use on every generic role-assignment path |
| `boolean totpApplies(clientId)`; `boolean totpRequiredFor(clientId, realmRoles)` | |
| `Optional<String> passwordGrantRefusal(Map claims, clientId)` | from the just-issued token's claims (exemption + `acr`); fails open |
| `void enforcePasswordGrant(claims, username, clientId, ip, door)` | 403 `mfa_required`; audited `auth.mfa.password_grant_refused` |
| `void invalidate()` | drop caches |

**`MfaBulkEnrolment`**: `Map startAsync(actor)` (single flight; audited `admin.mfa.bulk_enrol` with the
summary when done), `Summary run(boolean dryRun)`, `Optional<Map> progress()`. Idempotent (skips enrolled,
pending, disabled, service accounts and every `mfa-exempt` holder); resumable — Redis
`mfa:bulk-enrol:checkpoint` (JSON summary, TTL 30 d); lock `mfa:bulk-enrol:lock` (2 h lease).

**`MfaFlowInstaller`**: `Report install(Options)` / `install(realm, Options)`; `Options(apply, rebuildFlow, bind,
defaultActions)` (`Options.applyDefaults()`, `Options.checkOnly()`); `Report(applied, findings, missingProviders,
blocked)`. Applies `mfa-policy:/gorsso/mfa-flow.json` (realm OTP/brute-force settings, `acr.loa.map`, mode
`off` only if unset, events listener, `mfa-exempt` role, required actions, `browser-totp` flow + configs, bind).
Refuses (changes nothing) when the server lacks the gorsso-mfa providers / recovery-codes.
`List<FlowNode> plan(orgRequirement)`, `List<FlowRow> rows(orgRequirement)`.
Ops commands: `mfa-install [--check] [--rebuild-flow] [--no-bind] [--default-actions]`, `mfa-enrol [--dry-run]`.

---

## 7. `service.mail` — the wrapper's own mail

Relay = `spring.mail.*` (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_STARTTLS`, `SMTP_USE_SSL`);
`sso.mail.from` (`SMTP_FROM`), `sso.mail.login-url` (`SSO_LOGIN_URL`), `sso.mail.notify-access-changes`
(`NOTIFY_ACCESS_CHANGES` — the caller checks it). Templates: `src/main/resources/templates/mail/*.txt` (plain text).

**`Mailer`** — never throws, never logs secrets, empty `SMTP_HOST` = `NOT_CONFIGURED`:
`boolean configured()`, `DeliveryResult send(to, subject, body)`, `sendWelcome(WelcomeMail)`,
`sendAccessGranted(AccessChangeMail)`, `sendAccessRevoked(AccessChangeMail)`, `sendSetupLink(SetupLinkMail, via)`,
`render*` (pure), `static appLabel(clientId, name)`, `static personName(first, last, username)`.
`WelcomeMail(userId, to, name, username, applications, institution, loginUrl /*null=setting*/, passwordEmailSent,
passwordSetByAdmin, setPasswordUrl, lifespanHours)` — never put a password in it.
`DeliveryResult(status SENT|FAILED|NOT_CONFIGURED, detail)`; `status.wire()` = `sent|failed|not_configured`; `report()`.

**Per-account delivery record** (`MailDeliveryLog`): `sendWelcome` (when it carries a link and `userId`) and
`sendSetupLink` record the outcome automatically. Key `setup-link:delivery:<userId>` → JSON
`{"status": "sent|failed|not_configured", "via": "provisioning|holder_resend|admin_resend", "at": ISO-8601}`,
TTL 30 days, deleted when the password is set. Read with `MailDeliveryLog.last(userId)` (overview buckets:
`failed / not_configured / unknown (no record) / sent`).

---

## 8. `service.setuplink.SetupLinkService` — set-password link (spec §5.12)

Rules: `policy.setuplink` (`SetupLinks`, `SetupLinkPolicy`). Redis (`RedisSetupLinkStore`, falls back to
in-process when Redis is down):

```
setup-token:<sha256>             {"user_id","hash","client_ids":[..],"expires_at":epochSeconds}   TTL 72 h
setup-token-user:<uid>           sha256 of the ONE live link                                      TTL 72 h
setup-clients:<uid>              ["client-id",...]                                                TTL 30 d
setup-link:last:<uid>            last holder resend (epoch s)                                     TTL 5 min
setup-link:day:<uid>:<yyyyMMdd>  holder resends that day                                          TTL 1 d
setup-link:delivery:<uid>        see §7                                                           TTL 30 d
setup-link:admin-resend:<actor>:<epochMinute>   admin resend counter                              TTL 90 s
```

| Method | Semantics |
|---|---|
| `IssuedLink issue(userId, clientIds)`; `String issueUrl(userId, clientIds)`; `String setupUrl(token)` | one live link per account (new kills old); URL `<API_BASE_URL>/account/setup?token=…`; only the hash is stored |
| `List<String> bindClients(userId, clientIds)`, `void revoke(userId)` | |
| `Delivery recordDelivery(userId, status, via)`, `Optional<Delivery> lastDelivery(userId)` | |
| `LinkCheck open(token)` | GET: `refusal` null = usable (`link_unknown`, `password_already_set`); never spends |
| `Completion complete(token, password, confirm, ip)` | POST: `outcome` = `completed` \| link refusal \| `password_empty` \| `passwords_differ` \| `password_rejected` (+Keycloak `message`); only `completed` spends; returns `applications` the person can open now; audited `auth.setup_link.used` |
| `void setPassword(userId, password)` | reset-password `temporary=false`, remove `UPDATE_PASSWORD`, `emailVerified=true`; policy refusal → **400 `password_rejected`** with Keycloak's message |
| `List<AppLink> applications(user, clientIds)` | re-checked through `ClientAccessService.checkLogin`; `{client_id, name, url}` |
| `String requestResend(identifier, ip)` | holder resend: returns the REAL outcome (`sent`, `no_account`, `cooldown`, `daily_limit`, …) for audit/tests — **show `SetupLinkPolicy.PUBLIC_MESSAGE` whatever it is**; never throws; per-account cooldown (5 min) + daily limit (5); audited `auth.setup_link.resend`. Put an IP `@RateLimit` on the route too |
| `AdminResend adminResend(userId, actor, institution, ip)` | only after a `failed`/`not_configured` delivery (`outcome` + human `message` otherwise); 10/min per actor → 429 `rate_limited`; no relay → `not_configured` (nothing issued); audited `admin.setup_link.resend`. The caller checks `manage-members` and that the account is its institution's |
| `static SetupAccount account(UserRepresentation)` | |

---

## 9. `service.serviceaccount.ServiceAccountRoleReconciler`

On `ApplicationReadyEvent`, on a background thread, adds missing `realm-management` roles to the
`sso-client` service account: `REQUIRED_REALM_MANAGEMENT_ROLES` = view-realm, view-users, view-clients,
view-events, view-authorization, query-users, query-clients, query-groups, query-realms, manage-users,
manage-clients, manage-authorization, manage-realm. Uses the master admin (`KEYCLOAK_ADMIN_USERNAME` /
`KEYCLOAK_ADMIN_PASSWORD`, realm `KEYCLOAK_ADMIN_REALM`=master); skipped when no password; never throws.
`sso.service-account-roles.reconcile` (`RECONCILE_SERVICE_ACCOUNT_ROLES`). `static Result reconcile(RealmResource, clientId)` is reusable.

---

## 10. Test harness — `support/KeycloakIT` and `support/RedisIT`

Tag tests `@Tag("it")`; run `mvn -B -f sso-wrapper/pom.xml -Pit verify`.

**`KeycloakIT`** — one `quay.io/keycloak/keycloak:26.2` container per test JVM (features
`organization,admin-fine-grained-authz:v2`), started and seeded on first use (~45 s):
- realm `government-internal` imported from `configs/realms/government-internal-realm.json` (stripped:
  `loginTheme`, the legacy UPPERCASE `MINAGRI`/`MINISANTE` groups; `mfa_totp_mode` reset to `off`;
  user profile `unmanagedAttributePolicy=ENABLED`; password policy kept: `length(10) and upperCase(1) and lowerCase(1) and digits(1)`);
- `sso-client` (secret `KeycloakIT.SSO_CLIENT_SECRET`) with service-account roles as §9; `sso-admin-ui`;
  `gorsso-it-cli` (public, direct grants, `institution` claim mapper) for user tokens;
- groups `/minagri`, `/minagri/planning`, `/minisante`; roles `inst:<slug>:admin|hr|staff|auditor`,
  `super_admin|admin|system_admin`; clients `minagri-kubaka-web`, `minisante-hmis`;
- users (password `KeycloakIT.PASSWORD` = `Gorsso-It-2026`, attribute `institution`): `minagri.admin`,
  `minagri.hr`, `minagri.staff`, `minagri.planner` (in `/minagri/planning`), `minisante.admin`,
  `minisante.hr`, `minisante.staff`, `platform.super` (super_admin).

API: `container()`, `serverUrl()`, `masterAdmin()` (shared, don't close), `realm()`, `ssoProperties()`,
`adminFactory()`, `executors()`, `register(DynamicPropertyRegistry)` (for `@SpringBootTest`),
`token(username[, password])`, `tokenResponse(clientId, username, password)`, `caller(username)` (a real
`CallerPrincipal` with the real token → as-caller paths), `claims(jwt)`, `unique(prefix)`,
`createUser(username, institution, realmRoles...)` / `createUser(username, email, institution, attributes, realmRoles)` → id,
`createClient(clientId[, attributes])` → uuid (confidential, rootUrl `http://localhost:9999`, baseUrl `/home`),
`userId(username)`, `groupId(path)`, `clientUuid(clientId)`, `ensureRealmRole(name)`, `locate(relativePath)`.
Use `unique(..)` names — the realm is shared by all test classes. `service/ItServices` wires the shared services by hand against it.

**`RedisIT`** — one `redis:alpine` container: `template()` (`StringRedisTemplate`), `connectionFactory()`,
`host()`, `port()`, `flush()`.

---

## Gaps / notes

- The policy refusal code for missing templates is `delegated_config_unavailable`; the API answers
  `delegation_unavailable` (mapped in `InstitutionContextService.toApiException`).
- Error codes `identifier_malformed`, `identifier_taken`, `password_rejected` are constants on the services
  (not yet in `error/ErrorCodes`).
- `KEYCLOAK_ADMIN_PASSWORD` is not in `FileBackedSecretsPostProcessor.FILE_BACKED` (core file) — add it there for `_FILE` support.
- Per-client write serialisation is per JVM; two wrapper instances can still race on one client's policies
  (as in the reference).
- The flow installer cannot be exercised end-to-end against the plain `keycloak:26.2` image (no gorsso-mfa
  providers); its plan is tested against `infra/terraform/mfa.tf`, and the IT checks the "providers missing → nothing changed" path.
