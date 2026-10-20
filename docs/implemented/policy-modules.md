# Pure policy modules (SPEC §2)

Rule-for-rule ports of the reference wrapper's pure `src/app/core/*.py` modules, in
`sso-wrapper/src/main/java/rw/gov/risa/sso/wrapper/policy/<module>/`, with tests in the
mirrored test packages.

**Rules for every module.** Plain Java 21: records, enums and sealed interfaces. No Spring,
servlet, `org.keycloak`, `java.net.http` or Jackson annotations. This is enforced by
`sso-wrapper/src/test/java/rw/gov/risa/sso/wrapper/ArchitectureTest.java`, which has three
rules:
- `policyModulesArePureJava`
- `policyModulesHaveNoJacksonAnnotations`
- `policyModulesDoNotDependOnApiOrService`

**How services use them.** Services adapt Keycloak representations into the input records,
for example `Map<String, List<String>>` attributes or role-name lists. Jackson
(`ObjectMapper`/YAML) is used only inside the thin loaders that are named below.

**Wire codes.** Machine codes are snake_case wire strings. They are exposed through
`code()` or `wire()` on enums.

**Messages.** Messages copy the reference text. Python `repr` formatting is kept, for
example `'minagri'` and `['a', 'b']`.

**Test totals:** 872 policy tests and 3 ArchUnit rules, all passing.

| Module | Tests |
|---|---|
| delegated | 197 |
| fgap | 27 |
| institutioncontext | 62 |
| clientscope | 91 |
| joinkey | 78 |
| employeeaccounts | 61 |
| registry | 39 |
| ippisperson | 64 |
| clientaccess | 59 |
| lifecycle | 16 |
| audit | 6 |
| accessmatrix | 40 |
| accessmatrixsheet | 73 |
| setuplink | 36 |
| overview | 23 |

Surefire's `.txt` summary shows 0 for classes that use `@Nested`. Count `<testcase>` in
the XML reports instead.

---

## delegated — `policy.delegated` (ref `delegated_permissions.py`)

| Class | Key API |
|---|---|
| `enum Capability` | `VIEW_MEMBERS("view-members")`, `MANAGE_MEMBERS`, `MANAGE_MEMBERSHIP`, `MAP_ROLES`, `VIEW_CLIENTS`, `MANAGE_CLIENTS`; `String wire()`, `static Optional<Capability> fromWire(String)` |
| `DelegatedPermissionConfigException extends RuntimeException` | Missing or unsafe config. Map it to 503 on `/institution-admin/*`, or to 400 for `grantFor`/preview. |
| `DelegatedNamespaces` | `isValidSlug`, `rolePrefix(slug)` → `inst:<slug>:`, `clientPrefix(slug)` → `<slug>-`, `groupPath(slug)` → `/<slug>`, `render(pattern, slug)`, `parseInstitutionRole(role)` → `Optional<InstitutionRole(slug, shortName)>` |
| `Glob` | Exact `fnmatchcase` port: `matches(name, pattern)`, `matchesAny(name, patterns)` |
| `record Guardrail(Set<String> forbiddenRealmRoles, Map<String,List<String>> forbiddenClientRoles)` | `List<GuardrailViolation> violations(Collection<String> realmRoles, Map<String,? extends Collection<String>> clientRoles)`. `realm-management:["*"]` forbids **any** realm-management role. |
| `record GuardrailViolation(Kind kind, String role, String client)` | `toString()` gives `realm role admin` or `realm-management:manage-users` |
| `record ResolvedGrant(institution, templates, Set<Capability> capabilities, groupPath, includeSubgroups, roleAllow, roleDeny, clientAllow, clientDeny)` | `has(Capability)`, `capabilityNames()`, `mayMapRole(role)`, `mayViewClient(id[, shared])`, `mayManageClient(id[, shared])`, `coversGroupPath(path)`, `describe()`, `static merge(List)`. Allows add up across tiers; denies are absolute. |
| `record PermissionTemplate(...)` | `render(slug)`, `grantRoleFor(slug)`, `slugForRole(role)`, `rolesToProvision(slug)` |
| `record DelegatedPermissionRegistry(version, source, guardrail, templates, defaultTemplates, byInstitution)` | `Map<String,ResolvedGrant> resolveCaller(Collection<String> realmRoles)`, `ResolvedGrant grantFor(slug)`, `enabledTemplates(slug)`, `configuredInstitutions()`, `grantRolesIn(roles)`, `describe()` |
| `DelegatedPermissionsParser` | `static DelegatedPermissionRegistry fromMap(Object raw, String source)`. Validates every config-load rule: unsafe templates and allow patterns outside the namespace are rejected. |
| `DelegatedPermissionsLoader` (Jackson) | `loadFrom(Path)`, `parse(String json, String source)`, `locate(configured, extraBases)`, `DEFAULT_CONFIG`. To reload safely, parse first and swap only on success. |
| `record InstitutionPreview` | `static of(registry, slug)`, for `GET /delegated-permissions/institutions/{slug}` |
| `record RoleProvisioningPlan` | `static plan(registry, slugs, existingRoles)`, `toCreate()`, `unchanged()`. This is the pure half of `delegated-apply`. |
| `DelegatedAudit` | `static Report audit(registry, RealmView, institutions /*null = discover*/, platformRoles)` and `Report.clean()`. This is the pure half of `delegated-check` and `GET …/audit`. The service implements `RealmView`. |

## institutioncontext — `policy.institutioncontext` (ref `institution_context.py`, pure parts)

| Class | Key API |
|---|---|
| `record CallerIdentity(username, userId, List<String> realmRoles, Map<String,List<String>> clientRoles, String institutionClaim)` | Built from the verified JWT. `clientRoles` = `resource_access.*.roles`; it **must** include `realm-management` so the guardrail can see it. |
| `interface GrantLookup` | `resolveCaller`, `grantFor`, `guardrail`; `static GrantLookup of(DelegatedPermissionRegistry)` |
| `record InstitutionContextSettings(boolean guardrailEnforce, boolean claimCrosscheck)` | `defaults()` = both true. These correspond to `DELEGATED_GUARDRAIL_ENFORCE` and `DELEGATED_CLAIM_CROSSCHECK`. |
| `final class InstitutionContextResolver` | Constructors: `(GrantLookup, settings)`, `(Supplier<GrantLookup>, settings)`. The supplier may throw the config exception, which becomes 503. Methods: `Resolution<InstitutionContext> resolveInstitutionAdmin(CallerIdentity, String requestedInstitution)`, `Resolution<InstitutionContext> requireCapability(caller, requested, Capability)`, `Resolution<DirectoryScope> resolveDirectoryScope(caller, requested)`, `static guardrailViolations(lookup, caller)`, `static claimContradicts(claim, chosen)`, `PLATFORM_ROLES`. |
| `sealed interface Resolution<T>` | `Resolved(T value, warnings)` or `Refused(Refusal, warnings)`; `orThrow()`, `isRefused()`. `warnings` holds what to log at WARN; it is filled even when the setting is off. |
| `record Refusal(int status, Code code, String message)` | Codes, with HTTP status: `delegated_config_unavailable` 503, `guardrail_violation` 403, `institution_claim_mismatch` 403, `not_institution_admin` 403, `institution_not_administered` 403, `institution_required` 400, `multiple_institutions` 400, `unknown_institution` 400, `capability_denied` 403, `role_not_assignable` 403, `client_out_of_scope` 403. `toException()` → `InstitutionRefusedException`. |
| `record InstitutionContext(actor, userId, institution, grant, administered, platformAdmin, realmRoles)` | `groupPath()`, `rolePrefix()`, `clientPrefix()`, `capabilities()`, `checkCapability`/`assertCapability(Capability)`, `checkRoleInNamespace`/`assertRoleInNamespace(role)`, `shares(ClientScope)`, `checkClientInScope`/`assertClientInScope(clientId, ClientScope scope /*nullable*/[, Capability])`. Each `check*` returns `Optional<Refusal>`; each `assert*` throws. The bearer token is **not** in this record; the service carries it for execute-as-caller. |
| `record DirectoryScope(actor, userId, institution /*null = all, platform only*/, administered, platformAdmin)` | `isScoped()` |

## fgap — `policy.fgap` (ref `fgap_v2.py`)

| Class | Key API |
|---|---|
| `FgapPlanRenderer` (CLI entry) | `static String render(DelegatedPermissionRegistry, List<PlanInstitution>)` uses LF; `render(registry, institutions, String lineSeparator)`; `static boolean isUpToDate(String committed, registry, institutions)` ignores CRLF/LF differences and is what `fgap-plan-check` should use; `build(...)` returns the `Map`; constants `TEMPLATES_PATH`, `REGISTRY_PATH`, `OUTPUT_PATH`, `BANNER`. **The committed `configs/generated/fgap-v2-plan.json` uses CRLF.** `render(..., "\r\n")` reproduces it byte for byte, and this is tested. |
| `FgapPlanInputs` (Jackson YAML) | `fromInstitutionsYaml(Path\|String)` → `List<PlanInstitution(slug, apps, departments)>`. Only active, shared-realm entries are kept. |
| `FgapProjection` | `renderInstitution(registry, slug[, roles, clients, subgroups, SubgroupMode])` → `InstitutionPlan(institution, List<RolePolicy>, List<ScopePermission>)`, with one permission per (resource type, scope, tier-set). Also `renderShared`, `renderAll`, `assertPlanSafe(plan, guardrail)`, `policyName`, `permissionName`, `isManagedName`, `parseManagedName`. |
| `FgapDrift` | `diff(plans, Map<String,ActualPolicy>, Map<String,ActualPermission>)` → `List<Finding(Kind, name, detail)>` |
| `PyJson` | `dumps(Object, indent)`, matching Python `json.dumps(indent=2)` |

## clientscope — `policy.clientscope` (ref `client_scope.py`)

| Class | Key API |
|---|---|
| `record ClientScope(clientId, owner, crossCutting, allInstitutions, institutions, inferred)` | `kind()` (`ClientScopeKind` `institution`/`cross-cutting`/`platform`), `covers(slug)`, `covered(knownSlugs)`, `coversNobody()`, `isScoped()`, `toAttributes()`, `describe()`, `static parse(clientId, Map<String,?> attributes, knownSlugs)` (tolerant; infers the owner from a legacy `<slug>-` prefix), `static requested(clientId, crossCutting, institutions, ownerInstitution, knownSlugs)` (strict; throws `ClientScopeException` → 400), factories `platform`/`forInstitution`/`forAllInstitutions`/`forSelected` |
| `ClientScopes` | `ATTR_OWNER`, `ATTR_CROSS_CUTTING`, `ATTR_INSTITUTIONS`, `ALL_INSTITUTIONS="*"`; `ownerFromClientId(id, knownSlugs)` (longest prefix wins); `clientIdFor(institution, app)`, which composes `<slug>-<app>` for `POST /institution-admin/apps`; `scopeAttributesIn(keys)` (refuse a generic update); `institutionsOutsideScope(...)`; `removedInstitutions(...)` |
| `ScopeChangePolicy` | `static Plan evaluate(ClientScope before, ClientScope after, knownSlugs, Map<String,InstitutionAccess(institution, groupAssigned, users)> impact, boolean confirm)` → `Plan.confirmationRequired()` (409) and `conflictDetails()`. Once confirmed, withdraw access for `plan.removedInstitutions()`. |
| `DelegatedAppRules` | `crossCuttingRefusal(scope, Operation{JOIN_COLUMN, MFA, SECRET})` |

## joinkey — `policy.joinkey` (ref `join_key.py`)

| Class | Key API |
|---|---|
| `enum JoinColumn {NID, PHONE, EMAIL}`, `enum JoinKeyError` | `JoinKeyError.code()` = `join_key_missing\|join_key_invalid\|join_key_not_unique` |
| `JoinKeys` | `ATTR_JOIN_COLUMN` (`sso.client.join_column`), `JOIN_KEY_CLAIM="join_key"`, `JOIN_KEY_MAPPER_NAME="sso-join-key"`, `specFor(column)`, `requested(String)` (strict, `JoinColumnException` → 400), `choices()` |
| `record ColumnSpec` | `read(JoinKeyUser)`, `normalise`, `validate(value)` (NID 16 digits, phone 9–15 digits, email), `mapper()` → `MapperSpec(id, name, protocol, protocolMapper, config)`. email maps a user property; nid and phone map attributes. |
| `record JoinColumnSetting(clientId, column, explicit, changedBy, changedAt, malformed)` | `static parse(clientId, attributes)`: unset = EMAIL with `explicit=false`. `toAttributes(actor, Instant)`, `describe()`. |
| `JoinKeyResolver` | `resolve(setting, JoinKeyUser, Collection<JoinKeyUser> holders /*null = skip uniqueness*/, validateFormat)` → `JoinKeyResolution(ok, column, value, error, message, conflicts)`; `readiness(setting, users, validateFormat)` |
| `JoinColumnChangeGate` | `evaluate(before, column, activeSessions, assignedUsers, assignedIsFloor, confirm)` → `Decision.confirmationRequired()` (409). Live sessions, or re-keying a configured client that has users, require confirmation; a first-time set does not. `isRekeying(before, col)`, `protocolRefusal`. |
| `MapperReconciler` | `plan(setting, existingMappers)` → `Action(CREATED\|UNCHANGED\|UPDATED, write)`; `writeFailedMessage` (502) |
| `IdentifierWriteCheck` | `candidates(...)`, `formatRefusal` (400), `duplicateRefusal(candidate, holders, selfId)` (409) |
| `JoinKeyGate` | `appliesTo(username, clientId, enforce, exempt)`, `enforcedFor(setting, enforceUnsetClients)`, `refusalBody(resolution, setting)` (403) |
| `JoinColumnBacklog` | `summarise(institution, List<App>)` for `GET /apps/join-columns` |

## employeeaccounts — `policy.employeeaccounts` (ref `employee_accounts.py`)

| Class | Key API |
|---|---|
| Code enums | `SkipReason` (email_missing, email_invalid, email_duplicate, no_qualifying_client, nothing_to_assign, employment_ended, not_found, account_ambiguous, account_other_institution), `DenyReason` (join_column_unset, join_value_missing, join_value_invalid), `AccountStatus` (existing, created, not_created), `PlanAction`, `Outcome` |
| Inputs | `EmployeeRecord(employeeNumber, email, phone, nationalId, firstName, lastName, institutionSlug, status, keycloakUserId)`, `ClientTarget(clientId, JoinColumnSetting, name)` |
| `AccountResolver` | `static Resolution resolve(List<EmployeeRecord>, String institution, AccountDirectory)`, which resolves the whole batch first in the order mirror link → `employee_number` attribute → unshared email. Outcomes are `account_ambiguous` or `account_other_institution`. `Resolution.applyTo(records)`, `refusalPlans()`. |
| `HeldClients` | `compute(targets, userIdsByNumber, holdersByClient, groupIdsOfUser)`. Deny beats allow; held apps are skipped. |
| `EmployeeAccountPlanner` | `plan(records, targets, validateFormat, held)` → `List<EmployeePlan>`. Validation is per (employee, client); "no email" and "no qualifying client" apply only at creation. Also `planEmployee(...)`, `totals(plans, targets)`. |
| `ProvisionRequestRules` | Request-level 400s: `clientIds`, `employeeNumbers(list, maxBatch)`, `order`, `notFound` |
| Report | `ReportRow`, `ProvisionReport(institution, dryRun, clients, totals, employees).describe()`. Per employee the report gives `account` plus `clients_granted`, `clients_skipped`, `clients_denied`. |

## registry — `policy.registry` (ref `institution_registry.py`, `validate_institution_registry.py`)

| Class | Key API |
|---|---|
| `InstitutionRegistry` | `static fromRaw(Object yamlTree, source)` (tolerant), `slugs()`, `codes()`, `entryFor(slug)`, `isKnownSlug`, `slugForIppisCode(code)`, `slugForLegacyName(name)` (`MINAGRI` → `minagri`), `warnings()` |
| `InstitutionRegistryLoader` (Jackson YAML) | `load(Path)`, `loadOrEmpty(Path)`, `parseYaml(String)` |
| `RegistryValidator` | `static RegistryFindings validate(Object registryDoc, Map<String,?> templatesDoc)` checks slug rules, the prefix-collision rule, ippis_code uniqueness, client-id collisions and tiers. `RegistryFindings.exitCode(strict)` serves `registry-validate`. |
| `Slugs`, `RegistryEntry` | `isValidSlug`, `normalise`, `isHyphenExtension` |

## ippisperson — `policy.ippisperson` (ref `ippis_person_view.py`)

| Class | Key API |
|---|---|
| Models | `IppisPerson`, `IppisEmployment`, `IppisJob`, `IppisEmployeeRecord`, `EmploymentStatus` |
| `IppisPersonViews` | `primaryEmployment(person)`: PRIMARY wins, ties go to the earliest start, and genuine ambiguity gives no institution. `viewOf(person)` → `PersonView` (`isLeaver()` = no open post anywhere), `viewOfEmployee(flat)`. |
| `IppisAttributeProjection` | `plan(userAttrs, PersonView, InstitutionRegistry, clearMissing)` → `AttributeSyncPlan(write, unmappedInstitution, ambiguousInstitution, …)`. It writes institution (registry slug), position, national_id, phone and ippis_status, and never email. `resolveInstitutionCode`. |
| `IppisReconcilePolicy` | `dispatch`, `leaverDecision`, `matchState`, `newWatermark`, plus `skipped`/`notFound`/`noUser` outcomes; `IppisOutcome` and `ReconcileSummary` |

## clientaccess — `policy.clientaccess` (ref `client_access_decision.py`)

| Class | Key API |
|---|---|
| `record AccessPolicy(id, name, type, logic, Map<String,String> config)` | Plain form of a Keycloak authz policy. `config.users`/`groups` hold the raw JSON strings. |
| `ClientAccessDecider` | `static AccessDecision decide(userId, directGroupIds, clientId, Collection<AccessPolicy>, exemptClients, boolean defaultDeny)`, plus the overload taking `ClientPolicies`. Policies are read **by type**. The deny list is checked first and beats every allow; group membership counts direct members only; a malformed policy is skipped. |
| `AccessDecision` | `state()` → `AccessState` granted/open/revoked/not_granted/exempt/unknown; also `reason()` (`AccessReason`) |
| `ClientAccessWrites` | `admitUser(ClientAssignment, userId)` → `AdmitResult` (adds the named grant and lifts the denial); `admitUsers`, `denyUsers`, `allowUsersAgain`, `revokeUsers`, `withdrawInstitution`, `institutionAccessSummary` |
| `CanonicalPolicies` | `plan(currentPolicies, desiredAssignment)` → `List<PolicyChange>` for `sso-access-groups`, `sso-access-users` and `sso-access-denied-users` |
| `UserAccessChange` | `check(Action, expectedState, decision)` → `state_changed`/`not_applicable`/`denied_by_group` |
| `ClientAccessExemptions` | `DEFAULT_EXEMPT_CLIENTS`, `isExempt` |

## lifecycle — `policy.lifecycle` (ref `lifecycle.py`; pure parts and spec additions)

| Class | Key API |
|---|---|
| `OffboardingPlanner` | `plan(OffboardingInput, reason, triggeredBy, Instant)` → `OffboardingPlan` (sessions, disable, groups to remove, `inst:<slug>:*` roles to strip, `sso-access-users` clients to unname, audit extras); `rollback(Progress)` |
| `RehirePolicy` | `decide(enabled, PersonView, attrs)` → `Decision` (`REENABLE` only for automated leaver offboardings with an open post again). Marker attributes are `offboarded_by` and `offboarded_at`. |
| `ReassignmentPlan` | `plan(remove, add, nameToId)`, `refusal(userExists, enabled)` |

## audit — `policy.audit` (ref `audit.py`, pure parts)

`AuditEventType` (exact wire strings and `auditServiceType()`), `AuditOutcome`,
`AuditPayloads.build(...)`, and `AuditScope` institution helpers.

## accessmatrix — `policy.accessmatrix` (ref `access_matrix.py`)

| Class | Key API |
|---|---|
| `AccessMatrix` | `maskNid`, `nidOf`, `resolveAccounts(roster, accountsById)` → `Map<String,AccountMatch>` (`AccountState` existing/none/elsewhere/ambiguous, `locked()`), `cell(Holder)`, `itemRejection(...)`, `pageBounds`, `dedupeItems`, `SORT_ORDER`, `matches` |
| `BulkPlanner` | `validateRequest(action, channel, n)`; `plan(Action, items, columns, roster, accounts, holders, mayCreate)` → sealed `BulkPlan` = `Forbidden` (403 for the whole request when any item is outside the institution) or `Ready(rejected, writes, toProvision)`. Grant only adds and revoke only removes; a changed cell gives `state_changed`. Also `settle(...)`, `failed(...)`. |

## accessmatrixsheet — `policy.accessmatrixsheet` (ref `access_matrix_sheet.py`; Apache POI)

| Class | Key API |
|---|---|
| `AccessMatrixWorkbook` | `byte[] build(SheetMeta, List<SheetColumn>, List<SheetRow>, String hmacSecret)` produces a protected sheet with locked identity columns, `YES` dropdowns, every text cell as STRING, no NIDs, and a hidden signed `_meta` sheet. `ParsedSheet parse(byte[], secret)` throws `SheetError(code)` (codes include `not_exported_here` for a bad signature). |
| `AccessMatrixSheet` | `sign`, `verify` (constant-time), `isExpired(meta, now)` (30 days), `classify(...)`, `filenameFor` |
| `ImportPreview` | `frameRefusal(parsed, institution, now)` → `other_institution_file` (403) / `export_expired` / `no_columns_left`; `plan(parsed, editable, roster, accounts, hasAccess, mayCreate, dryRun)` → `Refused` or `Planned(Preview(items, summary, plan))`. A cleared YES is reported as `removal_ignored` and never revoked. Missing NID or email reach the plan through the `dryRun` callback. `PLAN_TTL` = 30 min. |

## setuplink — `policy.setuplink` (ref `setup_link.py`)

| Class | Key API |
|---|---|
| `SetupLinks(SetupLinkStore, TokenSource, Clock)` | State machine: `issue(uid, clients)` (one live link per account; 72 h), `lookup(token)` (by sha256), `check(lookup, account)` (GET; never spends; dead once UPDATE_PASSWORD is no longer pending), `completionRefusal(...)`, `spend(record)` (only after Keycloak accepted the password), `revoke`, `recordDelivery`, `lastDelivery`, `throttle`, `recordSent` |
| `SetupLinkStore` / `InMemorySetupLinkStore` | Storage interface (the Redis layout is in its Javadoc) |
| `SetupLinkPolicy` | `eligibility`, `adminResendRefusal(account, delivery, credTypes)` (only after `failed`/`not_configured`), `tokenHash`, `linkRefusal`, `safeReturnUrl`, `launchUrl` |

## overview — `policy.overview` (ref `overview.py`)

| Class | Key API |
|---|---|
| `Overview` | `institutionLeavers(...)`/`institutionLeaversSection`/`platformLeaversSection` (an enabled account, or a disabled one that still reaches an app); `passwordBacklog(accounts, deliveries, employeeNumberOf)` (buckets failed/not_configured/unknown/sent); `loginActivity(events, nowMs, userIds)` + `loginSection(activity, bruteForce, usernameOf, eventCount, sessions)` (24 h/7 d, distinct users, top 5, locked-now limited to users with a recent failure and at most 50, `truncated` at 10,000); `appAccessRow`/`institutionAccessSection`/`platformAccessSection` |
| Sections | `LeaversSection`, `PasswordSetupSection`, `LoginSection`, `AccessSection`, each with `available` and `error`; `OverviewReport` |

---

## Not ported here, or where behaviour differs

- **mfa:** no `policy.mfa` module. `mfa-policy` (a teammate) already holds the constants
  for reasons and exemptions, and the rest of `mfa_policy.py` (`validate_reason`,
  `password_grant_refusal`, `needs_bulk_enrolment`, `SwitchState`,
  `delegated_change_refusal`) belongs there too. If it does not land there, add a thin
  `policy.mfa` adapter.
- **Service-layer concerns (deliberately left out):** process-wide registry
  caching and reload, all Keycloak, Redis, mail and audit I/O, and the "fail open on
  infrastructure error" try/catch around the join-key gate.
- **Error codes we chose:** where the reference raised a bare HTTPException with only a
  message, the codes were chosen by us. These are the `Refusal.Code` values in
  institutioncontext, `BulkRequestRefusal`, `SheetError`, and the `ImportPreview`
  refusals.
- **Spec additions in lifecycle:** the strip-roles/grants steps and the re-hire rule
  extend the reference.
- **Kept quirk:** a failed provisioning `ReportRow` keeps its planned `clients_granted`,
  as the reference does.
- **Timestamp format:** `SetupLinks.recordDelivery` stamps `at` with a `Z` suffix; the
  reference uses `+00:00`.
