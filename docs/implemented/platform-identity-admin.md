# Platform identity administration (`sso-wrapper`)

Spec §4 (platform admin: Clients, Users, Agencies, Roles, Authorization), §5.4, §5.5, §5.6 (platform
side), §5.7 (client MFA) and §5.16. This is a port of the reference's `api/{client,user,agency,roles,authorization}.py`
and their services. Every route sits behind the admin-tier gate (`RouteRules.ADMIN_TIER`:
`super_admin` | `admin` | `system_admin`). Every Keycloak call uses the `sso-client` service account.
The actor in the audit trail always comes from the caller's token.

## Code locations

| What | Where |
|---|---|
| Controllers and request DTOs | `api/client/ClientController` + `ClientRequests`, `api/users/UserController` + `UserRequests`, `api/agency/AgencyController` + `AgencyRequests`, `api/roles/RoleController` + `RoleRequests`, `api/authorization/AuthorizationController` + `AuthorizationRequests` |
| Orchestration | `service/platform/ClientAdminService`, `UserAdminService`, `AgencyAdminService`, `RoleAdminService`, `AuthorizationAdminService` |
| Shared helpers | `service/platform/PlatformSupport`: service-account exec; client lookup by clientId **or** uuid; audit target fields (`target_client`, `target_user`, `target_username`, `target`, `institution`), ported from `audit_scope.py` |
| Config | `service/platform/PlatformProperties` (`sso.platform.enable-saml` = `ENABLE_SAML`), with a FEATURE BLOCK in `application.yml` |
| Shared services used | `ClientScopeService` (scope read and the 409/confirm/withdraw step), `JoinKeyService` (column, mapper, readiness, `validateIdentifierWrite`), `MfaService` (client requirement), `ClientAccessService` (the `sso-access-*` policies and the decision rule), `service.provisioning.AccessChangeNotifier` (the access-change emails) |

## Endpoints (74, matching the reference contract's operations)

**Clients** (`{client_id}` is the clientId; the uuid is also accepted)
- `GET`/`POST /client/clients`
  - On create, the scope is recorded in the same call.
  - `owner_institution`, or `cross_cutting` + `institutions` (`['*']` = all).
  - SAML is gated by `ENABLE_SAML`.
  - A duplicate clientId returns 409.
- `GET`/`PUT`/`DELETE /client/clients/{id}`
  - `PUT` refuses scope attributes and join-column attributes with 400.
  - Other attributes are **merged** into the existing map.
- `GET …/secret`: 400 for a public client.
- `POST …/regenerate-secret`
- `PUT …/redirect-uris`
- `PUT …/post-logout-redirect-uris`: values are `##`-joined; `+` reuses the redirect URIs.
- `GET`/`PUT …/scope`
  - 409 `confirm_required` before an institution with users is removed. `details` holds `removed_institutions`, `access_at_risk`, `current_scope` and `requested_scope`.
  - With `confirm=true`, that institution's group and named users are withdrawn.
- `GET`/`PUT …/join-column`
  - `?check=true` adds a readiness report.
  - 400 for an unknown column.
  - 409 `confirm_required` with `active_sessions` and `assigned_users`.
  - 502 when the mapper write fails.
- `GET`/`PUT …/mfa`: `PUT` is super admin only, in both directions (403 otherwise).

**Users**
- `GET /users/`: filters `client_id`, `agency_id`, `role`, `search`, `enabled`, `email_verified`; paging with `page` and `page_size` (1–100; out of range is 422).
- `GET /users/count`
- `POST /users/`
- `POST /users/provision`: create, then password, then groups. A failure rolls the account back.
- `GET`/`PATCH /users/{id}`
  - NID, phone and email writes go through `validateIdentifierWrite`: 400 `identifier_malformed`, 409 `identifier_taken`. This applies to create and provision as well.
  - `PATCH` merges attributes; an empty value clears a key.
  - `PATCH` with `enabled:false` is audited as `admin.user.disabled`.
- `PUT …/reset-password`: 400 when the password policy refuses the password.
- `GET …/groups`
- `PUT`/`DELETE …/groups/{groupId}`
- `POST`/`DELETE …/groups/bulk`
- `GET`/`DELETE …/sessions`
- `DELETE …/sessions/{sessionId}`: returns 404 unless the session belongs to that user.

**Agencies** (top-level groups)
- `GET`/`POST /agency/`
- `GET /agency/count`
- `GET /agency/metrics`
- `GET`/`PUT`/`DELETE /agency/{group_id}`
- `GET`/`POST …/members`: the body is `MemberAdd {userId}`.
- `DELETE …/members/{user_id}`
- `POST`/`DELETE …/roles`
- `GET`/`POST …/subgroups`: departments are read from `/groups/{id}/children`, because KC26's `subGroups` is always empty.
- `mfa-exempt` is refused (400) in any agency role assignment.

**Roles**
- Realm roles: `GET`/`POST /roles/realm-roles` and `PUT`/`DELETE /roles/realm-roles/{role_name}`.
- Client roles: `GET`/`POST /roles/clients/{client_id}/roles` and `PUT`/`DELETE …/roles/{role_name}` (client uuid as in the reference; the clientId is also accepted).
- User role mapping:
  - `GET /roles/users/{id}/roles`
  - `POST`/`DELETE /roles/users/{id}/realm-roles`
  - `POST`/`DELETE /roles/users/{id}/client-roles/{client_id}`
  - Roles are resolved by `name`. An unknown role returns 400. `mfa-exempt` returns 400.
- Catalogue: `GET /roles/statistics`, `GET /roles/clients`, `GET /roles/client-id/{client_id}/uuid` (`{"client_uuid": null}` when the client does not exist).

**Authorization**
- `GET /authorization/client-id/{client_id}/uuid` (404 when the client does not exist).
- Scopes CRUD under `/authorization/clients/{client_uuid}/scopes[/{scope_name}]`.
- Policies CRUD under `/authorization/clients/{client_uuid}/policies[/{policy_id}]`.
  - Typed `role` and `group` policies are supported. Other types go through the generic endpoint.
  - A refused write is never returned as a 2xx.
- `GET /authorization/clients/{client_id}/access`
  - Returns `{client_id, enforced, users:[{id,username}], groups:[{id,name}], denied_users:[{id,username}]}`.
- `PUT /authorization/clients/{client_id}/access`
  - Body: `{user_ids, group_ids}`.
  - An institution group outside the client's scope returns 400.
  - Named users have their own denial lifted.
  - Audited as `admin.access.policy_updated`, recording the users and groups added and removed.
- `GET /authorization/users/{user_id}/client-access`: one item per client, with the states `granted`, `open`, `revoked`, `not_granted`, `exempt` or `unknown`.
- `POST /authorization/users/{user_id}/client-access/{client_id}`
  - Body: `{action: grant|revoke|restore, expected_state}`.
  - 409 with `error` = `state_changed`, `not_applicable` or `denied_by_group`, and `details.current_state`.
  - Each change is audited per (user, client) as `admin.access.granted` or `admin.access.revoked`.
  - The person is emailed (one email, `NOTIFY_ACCESS_CHANGES`) only when the gate's answer actually changed.
  - The response is `{item, previous_state, unassigned, denied, lifted_deny, notified}`.

## Choices and differences from the reference

- **Tier gate.** The reference gated `/roles` on realm role `admin` only and gated `/users/{id}/sessions` on `citizen|employee|admin`, so super_admin was refused. Here every route is admin-tier (spec §5.16), except client MFA writes, which are super admin only.
- **`restore`** is accepted as an action. It is a grant that is only valid on a `revoked` cell. The console's name for this is "restore".
- **Generic client `PUT`.** The reference passed snake_case keys straight to Keycloak, which silently ignored them. Here they are mapped to Keycloak fields, and attributes are merged rather than replaced.
- **Users list.** It pages natively when there is no in-memory filter. Otherwise it filters, then pages, then enriches only the page.
  - Client roles in the enrichment are the user's direct mappings plus their direct groups' mappings, from one `role-mappings` read each.
  - The reference used effective (composite) roles per client: one call per client per user.
- **Clients list.** It drops the reference's per-client `agencies` enrichment, because pydantic dropped that field anyway.
- **Agency count.** `GET /agency/count` returns `{"count": n}` (top-level groups). The reference accidentally nested this.
- **Audit additions:**
  - Role updates are audited (`admin.role.updated`).
  - Authz scope and policy CRUD is audited (`admin.client.updated` and `admin.access.policy_updated`).
  - Password resets use `admin.user.password_reset`.
  - Secret rotation uses `admin.client.secret_regenerated`.
  - Join-column changes use `admin.client.join_column_changed`.
- **Not ported:**
  - The `access_token` query parameter on `/authorization/*`.
  - `ENABLE_PROVISIONING_ON_GRANT` (SCIM-style provisioning on grant).

## Tests

- **Controller tests:** `api/platform/{Client,User,Agency,Role,Authorization}ControllerTest`.
  - All of them extend `PlatformApiTestBase` (`@WrapperTest`, one shared context, the services mocked).
  - They cover every endpoint's happy path, 422 validation, the scope 409 confirm, the join-column 409 confirm, 502, identifier 400/409, institution admin 403, plain user 403, and anonymous 401.
- **Route reachability (spec §5.16):** `api/platform/PlatformRouteReachabilityTest` enumerates every `@RequestMapping` of the five controllers.
  - It asserts all 74 are declared.
  - super_admin, admin and system_admin are never refused (no 401/403/404/405).
  - An institution admin gets 403 on every route; an anonymous caller gets 401.
- **Service tests (Mockito):** `service/platform/{ClientAdmin,UserAdmin,AuthorizationAdmin}ServiceTest`, `PlatformSmallServicesTest` (agencies, roles). 43 tests, all passing.
- **IT:** `service/platform/PlatformAdminIT` (`@Tag("it")`, `support/KeycloakIT`). 4 tests, all passing:
  - client create → scope widen → grant → `granted` → stale 409 → revoke → `not_granted`;
  - departments from `/children` and subgroup create;
  - realm role mapping;
  - NID 400 and 409, with the attribute merge.
