# Institution admin (`/institution-admin/*`, except provisioning / access matrix)

Port of `api/institution_admin.py` + `services/institution_admin_service.py` (users, roles, apps,
revocation, overview, audit). Spec §4, §5.2, §5.3, §5.5–5.7, §5.13, §5.15, §7.1–7.2.
Provisioning, `access-matrix*` and `grants/bulk` are a different slice.

## Code

| Where | What |
|---|---|
| `api/institution/InstitutionAdminController` | `me`, `permissions`, `overview`, `audit`, `users*`, `roles`, `users/{id}/roles` |
| `api/institution/InstitutionAdminAppsController` | `apps`, `apps/join-columns`, `apps/{id}/{secret,users,access,join-column,mfa}` |
| `api/institution/InstitutionAdminAccessController` | `access/clients-for-users`, `access/revoke` |
| `api/institution/InstitutionAdminRequests` | request bodies (reference field names) |
| `service/institutionadmin/InstitutionMembers` | the member half of the mandatory filter: group from the template, department walk, `assertMember` (403 `not_institution_member`), `assertAllMembers` |
| `service/institutionadmin/InstitutionKeycloak` | every Keycloak call of the slice; takes the `KeycloakExec` the service chose |
| `service/institutionadmin/InstitutionUsersService` | users + roles |
| `service/institutionadmin/InstitutionAppsService` | apps, create (`<slug>-<app>`), secret, join column, MFA, institution-wide grant/withdraw, app users |
| `service/institutionadmin/AccessHolders` | `_client_holders`: who of the institution reaches a client, how (named / via group / denied) |
| `service/institutionadmin/AccessRevocationService` | flow B + `access/revoke` |
| `service/institutionadmin/InstitutionAdminOverviewService` | the four overview sections |
| `service/institutionadmin/InstitutionRoster` | the institution's roster from the IPPIS mirror (leavers section) |
| `service/institutionadmin/InstitutionAuditService` | scoped audit read via `platformops.audit.AuditServiceClient.listEventsForInstitution`, re-filtered here |
| `service/institutionadmin/InstitutionAdminProperties` | `sso.institution-admin.audit-read-timeout` (`INSTITUTION_AUDIT_READ_TIMEOUT`, 10 s) |

## Rules

- **Mandatory institution filter.** Every route takes `@CurrentInstitution(require = …)`. The
  institution always comes from the resolved context. `?institution=` naming an institution the caller
  does not administer is 403 `institution_not_administered`. No body field names an institution:
  `POST /apps` refuses any field other than `app`, `redirect_uris` and `post_logout_redirect_uris`
  with 400. Cross-institution users get 403 `not_institution_member`, checked before the user is
  read. Clients outside scope get 403 `client_out_of_scope`. An unknown client gets 404.
- **Capabilities per route** follow the reference: view-members (users GET, overview, audit),
  manage-members (users POST/PATCH/deactivate; POST also needs manage-membership), map-roles (roles
  and role mappings), view-clients (apps list, join-columns, join-column GET, app users,
  clients-for-users), and manage-clients (create, secret, access, join-column PUT, mfa, revoke). The
  services check the same capabilities again; app users, clients-for-users and revoke also check
  view-members.
- **Roles.** Only roles that pass the grant's `map-roles` allow and deny patterns can be assigned:
  `inst:<slug>:*` minus the denied ones, so `inst:<slug>:admin` is excluded for the admin tier. The
  namespace check runs before the 404 for an unknown role. A platform admin who acts for an
  institution gets that institution's ceiling.
- **Credentials** (`DelegatedExecution`, `DELEGATED_EXECUTE_AS_CALLER`, default true):
  - **As the caller:** group and member reads, user read/create/update/disable/logout, role-mapping
    writes, and `sso-access-*` policies of the institution's own clients.
  - **Service account, always and only after the wrapper's check:**
    - the client catalogue, the realm-role catalogue, client creation and secrets;
    - join column and MFA attribute writes (via the shared services);
    - a cross-cutting client's policies (`ClientAccessService.execFor`);
    - another institution's group on a shared client (its members are intersected away);
    - the overview's events, brute-force status and session stats.
- **App create**: `ClientScopes.clientIdFor(ctx.institution(), app)`. A pasted `<slug>-…` gets 400
  ("app part only"). Owner attributes are written, and so are confidential + authorization services
  + PKCE S256. The response does not carry the secret; get it from `GET /apps/{id}/secret`.
- **Cross-cutting apps.** Secret, join column and MFA are refused with 403 `cross_cutting_client`
  (`DelegatedAppRules`). The join column stays readable. MFA can be turned **on** only; `required:false`
  gives 403 (`MfaService.refuseDelegatedChange`).
- **Revocation** (§5.13):
  - **What it does.** A named grant is removed. Access through a group becomes an entry on
    `sso-access-denied-users`; the deny beats the allow and the group is never touched.
  - **Scope.** Users are checked per id; one outsider makes the whole request 403. Clients must be
    manageable, otherwise 403.
  - **Batching.** There is one policy read-modify-write per client. If one client fails, that client
    is reported as `failed` and the rest go ahead.
  - **Preview.** `preview: true` (alias `dry_run`) runs the same code and writes nothing.
    `all_clients` without `confirm_all_clients` is answered as a preview.
  - **Audit.** One `admin.client.updated` event per applied (user, client) pair, with
    `action=revoke_user_access`, mode, channel, `batch_id`, `unassigned` and `denied`.
  - **Email.** People with accounts get one email per request, about applied changes only, never on a
    preview. The email goes through the canonical `service.provisioning.AccessChangeNotifier` (Batch
    API) and `NOTIFY_ACCESS_CHANGES` applies.
- **Overview.** It has four independent sections, each with `available` and `error`. Groups are
  **expanded** on the institution side, intersected with the members. Without view-clients the access
  section reports "Your role does not include view-clients." Without the mirror, leavers reports
  "The IPPIS employee mirror is not configured."
- **Audit.** The `institution=<ctx>` filter is forced on the downstream query. The answer is filtered
  again: foreign events and events with no institution are dropped, `total` is recomputed, and
  `scope_filtered_out` reports how many were dropped. Downstream errors are surfaced: the upstream
  status, or 503 when it is unreachable.
- **Fail closed.** Missing or unsafe templates give 503 `delegation_unavailable` on every route
  (shared interceptor; tested end to end).
- **Rate limits** follow the reference: users GET 120/min, user writes 30, roles 60, apps GET 120,
  create 10, secret 30, join-columns and join-column GET 60, join-column PUT and mfa 15, access 30,
  app users 60, clients-for-users 30, revoke 15, overview 30, audit 60.

## Tests

- **MockMvc** tests are in `api/institution/*Test`. They share one context, `InstitutionAdminMvcSupport`,
  which wires the real resolver, controllers, services, `ClientScopeService` and `ClientAccessService`.
  Keycloak is replaced by a stateful fake: `KeycloakDirectory`, `AccessPolicyStore` and
  `InstitutionKeycloak` are mocked. The test classes are `InstitutionAdminControllerTest` (25),
  `InstitutionAdminAppsControllerTest` (21) and `InstitutionAdminAccessControllerTest` (10).
- **Isolation suite** (`rw.gov.risa.sso.wrapper.isolation`, spec §7.1), 236 tests:
  - Matches names in `ci/isolation-required.txt`:
    - `MembershipFilterIsolationTest`
    - `CannotReadAcrossInstitutionsIsolationTest`
    - `CannotModifyAcrossInstitutionsIsolationTest`
    - `ListAndSearchAreScopedIsolationTest`
    - `RoleScopingIsolationTest`
    - `BroadRoleGrantIsBlockedIsolationTest`
    - `ClientScopingIsolationTest`
    - `CrossCuttingClientScopeIsolationTest`
    - `JoinColumnScopingIsolationTest`
    - `ClientScopeIsPrefixBasedAndTheRegistryCompensatesIsolationTest`
    - `RouteLevelIsolationTest`
    - `ShippedConfigCannotReachAnotherInstitutionIsolationTest`
  - Extra: `AccessRevocationIsolationTest` and `DelegationFailClosedIsolationTest`.
- **Mockito** tests: `service/institutionadmin/InstitutionUsersServiceTest` (credential choice) and
  `InstitutionAuditServiceTest`.
- **`@Tag("it")`** test: `InstitutionAdminKeycloakIT` runs on the shared `KeycloakIT`. It covers
  creating an app, granting access, preview, and revoke. After the revoke the login check gives
  `DENIED` while the user is still in `/minagri`. It also checks as-caller: Keycloak itself answers
  403 to the delegated admin's own token (no FGAP permissions are applied in the container), and with
  the flag off the same call succeeds, filtered.

- **`@Tag("it")` isolation classes** (run in the CI isolation job with `-Pit`):
  - `EmployeeDirectoryScopingIsolationTest` (19, Postgres 16 Testcontainer seeded through the mirror
    writer). A scoped list never contains another institution's employments, and no parameter widens or
    drops the institution term. The detail of another institution's employee is 404, never 403.
    `nationalId` appears only on the detail. Only a platform admin reads across institutions.
  - `FgapKeycloakDirectIsolationTest` (6). It renders the FGAP v2 plan from the repo templates with
    `FgapPlanRenderer` and applies it with `FgapApplier` (the `fgap-apply` service). The real
    `minagri.admin` token then calls the Keycloak Admin API directly. Own members, department, role
    mapping and client are allowed; the other institution's are refused. The wrapper's as-caller path
    (list, get, role assignment) SUCCEEDS for the own institution.
  - `InstitutionAdminKeycloakIT`'s negative as-caller case uses a token with no delegated permission
    (`minagri.staff`). That keeps it independent of whether FGAP has already been applied to the shared
    container.
- `sh ci/check-isolation-reports.sh sso-wrapper/target/surefire-reports ci/isolation-required.txt`
  passes: all 14 required classes ran.

## Departments not covered by the applied FGAP plan

- **Why it happens.** On Keycloak 26.2, FGAP v2 group permissions do not reach a group's children. The
  plan therefore enumerates the departments that exist when it is applied (`SubgroupMode.ENUMERATE`).
  A department created after that (platform console, IPPIS) is not covered: Keycloak returns it with
  `access.view=false` and answers 403 to any read made with the delegated admin's token.
- **What used to break.** `InstitutionMembers.memberGroupIds` walks the departments as the caller, so
  the whole user list returned 403.
- **What happens now.** The walk skips such a department, both on `access.view=false` and on a 403
  while walking a department, and logs a WARN that names the department and the remedy. A 403 on the
  institution's root group still fails. The department's members stay out of the listing until the plan
  is re-applied with `fgap-apply` (its default mode projects against the realm, so the new department
  is picked up) or Terraform.
- **Tests.**
  - `MembershipFilterIsolationTest`: 2 new MockMvc cases.
  - `FgapKeycloakDirectIsolationTest.aDepartmentCreatedAfterTheApplyIsLeftOutUntilThePlanIsReappliedNeverBreakingTheListing`
    (Keycloak IT).

## Gaps

- None known in this slice.
