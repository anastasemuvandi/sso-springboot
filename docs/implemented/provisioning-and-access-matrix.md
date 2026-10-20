# Employee provisioning and the access matrix (`sso-wrapper`)

Spec §5.11, §5.14 and the access-change emails (§5.14, last bullet). This is a port of the reference
`services/employee_provisioning.py`, `access_matrix_transfer.py` and `access_notifications.py`, plus the
matrix half of `InstitutionAdminService`. The rules live in the pure modules `policy.employeeaccounts`,
`policy.accessmatrix` and `policy.accessmatrixsheet` (see `policy-modules.md`). The code described here
does the I/O around them.

## Endpoints (`/institution-admin/*`, SELF_SCOPED, institution from the caller's grant)

| Route | Capability (route + service) | Rate | Notes |
|---|---|---|---|
| `POST /employees/provision` | manage-members; the service also needs manage-membership and manage-clients | 10/min | Body `{employee_numbers, client_ids, dry_run}`. Report: `institution, dry_run, clients, totals, employees[]`. Each employee row carries `account`, `clients_granted`, `clients_skipped`, `clients_denied`, `outcome`, `welcome_email`, `password_email`, and `password`. `password` is the mode (`set_by_holder` or `shared_dev`), never a value. |
| `GET /access-matrix?clients=a,b` | view-clients + view-members | 60/min | Also takes `page`, `page_size`, `q`, `access=has\|none`, `access_client`, `gender=M\|F\|unknown`. NIDs are masked. |
| `GET /access-matrix/nid/{employee_number}` | view-members | 60/min | The full NID. Every call is audited as `admin.employee.nid_revealed`. Off-roster gives 404. |
| `POST /access-matrix/setup-email/{employee_number}` | manage-members | 10/min per caller | Allowed only when the last delivery was `failed` or `not_configured`; otherwise 409. Off-roster gives 404. `SetupLinkService.adminResend` enforces 10/min per caller and audits `admin.setup_link.resend`. |
| `POST /grants/bulk` | manage-clients + view-members | 30/min | Body `{action: grant\|revoke, items: [{employeeNumber, clientId}], channel: ui\|bulk}`. Results are per item. |
| `GET /access-matrix/export?clients=` | view-clients + view-members | 10/min | Returns a protected `.xlsx` with `Cache-Control: no-store`. Audited as `admin.access_matrix.exported`. |
| `POST /access-matrix/import` (multipart `file`, `approved_by`) | manage-clients + view-members | 10/min | Returns a preview and stores a 30-minute plan. |
| `POST /access-matrix/import/{import_id}/apply` | manage-clients + view-members | 10/min | Applies the stored plan once. Only the admin who previewed it may apply it. |

When there is no mirror (`IDENTITY_DATABASE_URL` unset), every route answers 503
`identity_db_not_configured`, after authorisation. Export and import also answer 503 `not_configured`
when `ACCESS_MATRIX_SIGNING_KEY` is unset.

## Code

`service/provisioning/`
- `EmployeeProvisioningService`: `run(ctx, Command)` returns a policy `ProvisionReport`. The order:
  1. Check the capabilities.
  2. Load the targets. Any client outside the caller's scope fails the whole request with 403 from `ctx.assertClientInScope`. An unconfigured client is still loaded and denies everybody.
  3. Run the 400 checks (`EMPLOYEE_PROVISION_MAX_BATCH`).
  4. Read the mirror, using the caller's institution term only. Anyone not found is reported `not_found`.
  5. Resolve accounts for the whole batch with `AccountResolver`, in this order: mirror link, then unshared email (username, then email), then the `employee_number` attribute. Refusals are `account_ambiguous` and `account_other_institution`.
  6. Work out the held clients with `HeldClients`. Policies are read first, and a denied user is not a holder.
  7. Plan with `EmployeeAccountPlanner` (per employee and client).
  8. Apply each employee:
     - Create the account if needed: username and email are the IPPIS email, `UPDATE_PASSWORD` is set, the email is unverified, and the identifier write is validated. Then set a temporary credential from `TemporaryPasswords`.
     - Call `admitUser` per client: per user, never through the group. Each grant is audited as `ADMIN_CLIENT_UPDATED` (`assign_user_access`, `batch_id`, `channel`).
     - Add a new account to the institution group (membership only).
     - Send email: a new account gets the welcome with the `SetupLinkService` link and its app list; an existing account gets "access granted". A shared dev password skips the link.
     - Record the mirror link.
     - Audit at the employee level.

  One employee's failure is that employee's row, and the rest of the batch continues. `devPassword()` honours `EMPLOYEE_PROVISION_DEV_PASSWORD` only when `DEBUG` is on.
- `DevPasswordStartupCheck`: at startup it logs WARN when the dev password is honoured and ERROR ("IGNORED") when it is set without DEBUG. It never logs the value.
- `ProvisioningKeycloak`: service-account Keycloak operations (catalogue, lookups, create, `setPassword`, group join). Lookups answer empty on failure.
- `EmployeeMirror`: `records(institution, numbers)`, `roster(institution)` (both `DISTINCT ON (employee.id)`, with status derived from open jobs), and `link(number, userId)` (an upsert on `uq_employee_sso_link_employee` that never throws).
- `AccessChangeNotifier` (bean name `provisioningAccessChangeNotifier`): see below.
- `ProvisionViews`: the wire records, with snake_case names as in the reference.

`service/accessmatrix/`
- `AccessMatrixService`: `columns` (400 for none or more than 25, 403 if outside scope), `rows` (mirror roster plus members, matched in memory by `AccessMatrix.resolveAccounts`), `matrix`, `revealNid`, `resendSetupEmail`, and `bulk`. `bulk` works like this:
  - Any employee outside the roster, or any client outside scope, gives 403 for the whole request before any holder read or write.
  - `BulkPlanner` applies the concurrency guard: grant only adds, revoke only removes, and a changed cell gives `state_changed`.
  - Each client gets one `admitUsers` or `revokeUsers` write, then `settle`.
  - Every applied item is audited per (user, client) under one `batch_id`, with `auditExtra` (`import_id`, `approved_by`).
  - Grants to people with no account go through `EmployeeProvisioningService` under the same batch id and channel.
  - Notifications are sent once per person.
- `MatrixKeycloak`: visible apps (`ClientScopeService.clientsVisibleTo`), the member walk run as the caller, holders (named or direct member of an admitted group, intersected with members, plus the deny flag), and writes. A cross-cutting client's policies and other institutions' groups use the service account (`DelegatedExecution`).
- `AccessMatrixTransferService`: `export`, `preview`, and `apply`.
  - `preview` runs signature, age and institution checks, `ImportPreview.plan`, and a provisioning dry run for people with no account (missing NID or email shows up here). It stores the plan.
  - `apply` loads the plan, checks the actor and institution (403, and the plan is not consumed), consumes it once (404 afterwards), then calls `bulk(GRANT, plan items, channel bulk, batchId=importId)` and audits `admin.access_matrix.imported`.
- `ImportPlanStore`: Redis key `access-matrix-import:<id>` with a 30-minute TTL. Redis or 503; there is no in-process fallback.
- `AccessMatrixProperties` (`sso.access-matrix.signing-key`).

`api/provisioning/`: `ProvisioningController` and `AccessMatrixController`. These are separate from the
`api.institution` controllers and share the path prefix.

Config: `application.yml`, block "provisioning-and-access-matrix":
- `EMPLOYEE_PROVISION_MAX_BATCH` (100)
- `EMPLOYEE_PROVISION_DEV_PASSWORD`
- `ACCESS_MATRIX_SIGNING_KEY`
- multipart limit 6 MB (the parser refuses files over 5 MB)

`NOTIFY_ACCESS_CHANGES` and `SSO_LOGIN_URL` come from the shared `MailSettings`.

## AccessChangeNotifier — for the other doors (revoke flow, platform user page)

`rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier` collects changes, then flushes them once per request:

```java
var batch = notifier.begin(AccessChangeNotifier.Kind.REVOKE, ctx.institution());
for (applied (user, client)) batch.add(AccessChangeNotifier.Recipient.of(userRep), clientId, Mailer.appLabel(clientId, name));
Map<String, String> notified = batch.flush();          // userId -> sent | failed: <why> | not_configured | no_address | disabled
Map<String, Integer> counts = AccessChangeNotifier.summarize(notified);
```

The rules:
- Existing accounts only.
- Applied changes only.
- On a preview, never call `flush()`.
- One email per person per batch.
- A second `flush()` sends nothing.
- `NOTIFY_ACCESS_CHANGES=false` reports `disabled`.
- It never throws.

## Invariants proven

| Invariant | Where |
|---|---|
| §7.5 The temporary password is never disclosed (response, logs captured with `OutputCaptureExtension`, rendered welcome body, audit) | `EmployeeProvisioningServiceTest.createdAccountsTemporaryPasswordNeverAppearsInResponseLogsEmailOrAudit`; `ProvisioningControllerTest.theReportNeverCarriesAPasswordValue`; `EmployeeProvisioningIT` |
| §7.6 The import applies only the stored plan: tampering after the preview changes nothing; tampering before it gives a bad signature | `AccessMatrixTransferServiceTest.applyUsesTheStoredPlanNotTheFileAndRechecks`, `aFileTamperedBeforePreviewHasABadSignature` |
| §7.6 Everything is re-checked at apply time; a cleared YES is never revoked | `applyReChecksAtApplyTime`, `aClearedYesIsReportedNeverRevoked` |
| Formula-injection-safe cells, no NID exported, signed and stamped export | `exportIsProtectedSignedStampedAuditedAndCarriesNoNid` |
| Validation per (employee, client); "no email" and "no qualifying client" apply only at creation | `validationIsPerEmployeeAndClientNeverAllOrNothing`, `noQualifyingClientMeansNoAccountButNoEmailIsACreationOnlyRule` |
| Held apps skipped; deny beats allow | `appsAlreadyHeldAreSkippedAndADeniedUserIsNotAHolder`, `heldThroughAnAdmittedGroupCountsAsHeld` |
| An existing account is never altered | `existingAccountIsOnlyGrantedAndToldNeverAltered`; `EmployeeProvisioningIT.anExistingAccountIsOnlyGrantedNeverAltered` (real Keycloak; the password still works) |
| Whole-batch account resolution (ambiguous, other institution, not found) | `accountsAreResolvedForTheWholeBatchFirst` |
| Per-user grant, never through the group | Unit test plus IT (the policy names the user; `groupIds` is empty) |
| Concurrency guard; whole-request 403; provisioning under the batch id; one email per person | `AccessMatrixServiceTest` |
| §7.1 isolation (real services, Keycloak mocked at the lowest layer) | `isolation/ProvisioningIsolationTest` (4), `isolation/AccessMatrixIsolationTest` (7) |
| Import plan TTL in real Redis, consumed once | `ImportPlanStoreIT` |

## Tests

| Class | Kind | Count |
|---|---|---|
| `EmployeeProvisioningServiceTest` | unit | 15 |
| `AccessChangeNotifierTest` | unit | 4 |
| `AccessMatrixServiceTest` | unit | 14 |
| `AccessMatrixTransferServiceTest` | unit | 9 |
| `ProvisioningControllerTest` | MockMvc | 5 |
| `AccessMatrixControllerTest` | MockMvc | 6 |
| `ProvisioningIsolationTest` | isolation | 4 |
| `AccessMatrixIsolationTest` | isolation | 7 |
| `EmployeeProvisioningIT` | `@Tag("it")`, KeycloakIT | 2 |
| `ImportPlanStoreIT` | `@Tag("it")`, RedisIT | 1 |

## Differences from the reference / gaps

- The export is signed with `ACCESS_MATRIX_SIGNING_KEY` (the spec), not `SECRET_KEY`.
- A provisioning row that failed keeps its planned `clients_granted` (policy quirk). The bulk path reports it as `failed` / `provisioning_error`, not `applied`. The reference had a bug here.
- The provisioning existing-account email uses `Mailer.appLabel` ("Name (client-id)") instead of raw client ids.
- `approved_by` is read with `@RequestParam`, which works for multipart form fields. Because of that, the OpenAPI document lists it as a query parameter.
- `EMPLOYEE_PROVISION_DEV_PASSWORD` is not in `FileBackedSecretsPostProcessor.FILE_BACKED` (a core file).
- Notifications are sent sequentially; the reference uses a concurrency of 5.
