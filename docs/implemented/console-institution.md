# Console — institution area (console-web, Phase B)

Delegated institution-admin console on the platform shell (`console-web/`).
Everything is scoped by the wrapper (`/institution-admin/*`, mandatory
institution filter); the console only renders. Conventions: `console-web/README.md`
("Institution area").

## Code locations (`console-web/src/features/institution/`)

| What | Where |
|---|---|
| Context (`/me` + `/permissions`), `useInstitution()`, `?institution=` scope | `context.tsx`, `api.ts`, `types.ts` |
| Layout, fail-closed 503 / not-an-admin screens, `CapabilityGate` | `components/` |
| Overview (four sections + cards, needs attention, recent activity) | `pages/OverviewPage.tsx`, `overview/` |
| Staff, staff roles (only `inst:<slug>:*`), roles, audit | `pages/Staff*.tsx`, `pages/RolesPage.tsx`, `pages/AuditPage.tsx`, `staff/`, `audit/` |
| Apps: list (owned vs cross-cutting, require-only 2FA, owned-only secret), new (app part only, fixed `<slug>-` prefix, live preview), users (revoke with server preview), join key (409 confirm), join-keys worklist | `pages/App*.tsx`, `apps/` |
| Employees + provisioning (selection across pages, dry run, report with the four facts) | `pages/Employees*.tsx`, `employees/` |
| Access matrix (virtualised TanStack Table + react-virtual, pending-change reducer, bulk grant/revoke, NID reveal, setup-email resend, Excel export), import (upload → preview → confirm → results), revoke (user-first / client-first with preview) | `pages/Access*.tsx`, `access/` |

## Endpoints

`/institution-admin/me`, `/permissions`, `/overview`, `/users[/{id}[/deactivate|/roles]]`,
`/roles`, `/audit`, `/apps[/{id}/secret|/users|/access|/join-column|/mfa]`,
`/apps/join-columns`, `/access/clients-for-users`, `/access/revoke`,
`/employees/provision`, `/access-matrix`, `/grants/bulk`,
`/access-matrix/nid/{n}`, `/access-matrix/setup-email/{n}`,
`/access-matrix/export`, `/access-matrix/import[/{importId}/apply]`; `GET /employees`
(scoped). Shapes follow the reference contract (`openapi.reference.json`).

## Tests

- Pure state modules (Vitest): `access/state/matrixState` (reference
  `access-matrix-state.mjs` cases + reducer), `access/state/revokePreview`,
  `access/state/importState`, `employees/state/employeeSelection` (across
  pages, select-all-on-page tri-state, cap 100, storage), `apps/state/appCreateForm`,
  `staff/state/staffForm`, `audit/state/auditView`, `overview/state/attention`.
- RTL + MSW: overview (section unavailable vs zero; tier without view-members;
  503 screen), matrix bulk apply with `rejected/state_changed`, import
  preview → apply, revoke user-first, provision report (dry run → confirm),
  app create body (app part only), join-key 409 confirm, staff roles
  (`inst:minagri:*` only), capability gates.
- Playwright: `e2e/employee-selection.spec.ts` (select across two pages →
  dry run report → confirm; select-all skips unprovisionable rows) alongside
  `auth-guard.spec.ts`, against `e2e/stub-server.mjs` (institution persona).

## Contract alignment (wrapper `openapi.json`, 171 operations)

Operation diff: every console call exists in the wrapper with the same method
and path. Only path-parameter NAMES differ (wrapper camelCase): `/employees/{employeeNumber}`,
`/institution-admin/apps/{clientId}/…`, `/institution-admin/users/{userId}/…`
— updated in the console. Schema names differ (Java class names):
`Overview`→`PlatformOverview`, `InstitutionOverviewOut`→`Overview`,
`InstitutionRowOut`→`Row`, `EmployeeSummaryOut`→`EmployeeSummary`,
`EmployeeOut`→`EmployeeRow`, `EmployeePageOut`→`EmployeePage`,
`EmployeeDetailOut`→`EmployeeDetail`, `AgencyResponse`/`AgencyDetailedResponse`→`AgencyView`,
`MemberResponse`→`MemberView`, `RoleResponse`→`RoleView`, `ProvisionReport`→`ProvisionReportView`.

Field/semantics mismatches found and fixed on the console side:

| Endpoint | Console assumed | Wrapper | Change |
|---|---|---|---|
| all typed responses | optional fields | every key written (Jackson `always`), springdoc marks none required | `unwrap()` returns `Present<T>`; bodies use `RequestSchemas` |
| request bodies (agency, client create/scope, roles, revoke, realm config, branding, SMTP) | explicit `null` for "unset" | schema not nullable; record null == omitted | send `undefined` (omitted) |
| `/institution-admin/*` query | `institution` | documented as required object `ctx` (doc defect; resolver reads `institution`) | gen:api rewrites `ctx`→`institution` |
| `POST /institution-admin/access-matrix/import` | `approved_by`, `institution` as form fields | query params; multipart part `file` only | moved to query |
| `GET /institution-admin/overview` | `?refresh=true` | no refresh param (no cache) | refresh = plain re-read |
| `POST /authorization/users/{id}/client-access/{client}` restore | `grant` + `expected_state: revoked` | supports `restore` (refused unless revoked; audited as restore) | send `restore` (user page + client access card) |
| `GET /realm/branding` | flat map | `{themes, display, custom_attributes{logo_url, primary_color, …}}` | `BrandingState`, form reads `custom_attributes` |
| `POST /realm/branding/logo` | PNG/JPEG/SVG/WebP/GIF ≤ 2 MB | JPEG/PNG/GIF/SVG ≤ 5 MB | client-side check aligned |
| `GET /institution-admin/users[/{id}]` roles | `realmRoles` | `roles` (detail only; list has none) | `memberRoles()`; Roles page shows "—" for holders |
| field errors | 400/409 `details.field` | 400 `identifier_malformed` / 409 `identifier_taken` with `details.field`; 422 `validation_error` `details.fields[{field,message}]` (Java names) | mapper handles both, `nationalId`→`national_id` |
| `GET /dashboard/activity/24h` | — | extra `truncated` | typed (optional) |

Wrapper issues to raise (not fixed here — Java is out of scope):
1. `@CurrentInstitution` arguments are documented as a required query object
   `ctx`; needs `@Parameter(hidden = true)` plus a documented `institution` param.
2. `GET /institution-admin/audit` sets `total` to the number of events kept on
   the page (as the reference did), so paging cannot use it; the console offers
   "next" whenever a page is full.
3. `GET /institution-admin/users` returns no roles per member (the reference
   enriched `realmRoles`), so `/institution/roles` cannot show holders without
   one request per member.
4. Most responses are untyped `Map<String,Object>` (≈50 console calls): typed
   DTOs would let `gen:api` catch drift; until then the console's hand types
   were checked against the Java services.

## Assumptions / gaps

- `npm run gen:api` generates from `sso-wrapper/openapi.json`;
  `openapi.reference.json` is kept only for `GEN_API_SOURCE=reference`.
- Response shapes for untyped endpoints (members, roles, audit, bulk results,
  import preview, revoke report, exemptions) follow the reference field names.
- Wrapper validation errors are mapped to form fields when the message or
  `details.field` names `national_id` / `phone` / `email` / `username`.
- Provisioning cap (100) is hard-coded (reference `EMPLOYEE_PROVISION_MAX_BATCH`);
  leavers are not selectable for provisioning (same rule as the matrix).
- One revoke summary (`access/components/RevokeSummary.tsx`) serves both the
  revoke page and `apps/:clientId/users`. The import page test stubs the
  multipart upload call (Node fetch + jsdom FormData hang) and checks the
  request (`importFormData`, `importPath`) separately.
- Kinyarwanda strings are drafted and need a native-speaker review.
