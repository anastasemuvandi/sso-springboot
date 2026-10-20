# console-web — GoRSSO / SSO.RISA admin console

React 18 + TypeScript (strict) + Vite. React Router v6 data routers, TanStack
Query/Table, Tailwind + shadcn/ui (Radix) + lucide, react-hook-form + zod,
react-i18next (English + Kinyarwanda), Vitest + RTL + MSW, Playwright.

The console **decides nothing**. It talks only to the BFF; the BFF holds the
tokens (HttpOnly cookie session) and relays the caller's own bearer to the
wrapper, which enforces every rule. Hiding a button or a route is cosmetic.

## Commands

Node is repo-local: from the repo root, `export PATH=$PWD/.tools/node/bin:$PATH`.

| Command | What |
|---|---|
| `npm run dev` | Vite on :5173; proxies `/api`, `/bff`, `/oauth2`, `/login` to the BFF (`CONSOLE_BFF_URL`, default `http://localhost:3001`) |
| `npm run build` | `tsc --noEmit && vite build` |
| `npm run typecheck` / `npm run lint` | strict TS / ESLint (0 warnings) |
| `npm test` | Vitest (unit + component tests, MSW) |
| `npm run test:e2e` | Playwright gate against `vite preview` of the build + `e2e/stub-server.mjs` (see below) |
| `npm run gen:api` | `openapi-typescript` → `src/api/schema.d.ts` from the wrapper's committed `../sso-wrapper/openapi.json` (the source of truth). `GEN_API_SOURCE=reference` generates from `openapi.reference.json` (the old FastAPI contract) as an opt-in fallback. The script rewrites the wrapper's undocumented `ctx` query object to the real `institution` param. |

## Layout

```
src/
  api/            client.ts (openapi-fetch + unwrap), errors.ts (ApiError), csrf.ts, schema.d.ts (generated), types.ts
  auth/           session.ts (GET /bff/session, useSession), unauthorized.ts (401 → re-check → /bff/login)
  app/            router.tsx (every route), guards.ts (route loaders), Providers.tsx, queryClient.ts
  components/
    ui/           shadcn/ui primitives (button, card, dialog, sheet, select, tabs, …)
    common/       PageHeader, QueryState (Loading/ErrorState/Unavailable), ConfirmDialog (useConfirm), DataTable, Field, CopyButton, toast (notify)
    shell/        ConsoleLayout, Sidebar, TopBar, nav.ts, LanguageSwitcher, ThemeToggle, SignOutForm
  features/<feature>/
    api.ts        query keys + queryOptions + mutation helpers for that feature
    types.ts      wire shapes the generated schema leaves untyped
    state/*.ts    PURE state modules (no React) + *.test.ts
    components/   feature-only components
    pages/*.tsx   one file per route, exporting `Component`
  i18n/           en.json, rw.json, index.ts
  test/           MSW server, render helper, fixtures
e2e/              Playwright specs + stub-server.mjs (BFF + wrapper stand-in)
```

Features: `dashboard`, `overview` (shared by both consoles), `clients`, `users`,
`agencies`, `roles`, `employees`, `audit`, `settings`, `api-explorer`, `system`
(404 / error / no-access) and **`institution`** (the delegated-admin area).

## Adding a page

1. Create `src/features/<feature>/pages/<Name>Page.tsx` exporting
   `export function Component() { … }` (React Router `lazy` convention).
2. Add one route in `src/app/router.tsx` under the right area:
   `{ path: "things/:id", lazy: () => import("@/features/things/pages/ThingPage") }`.
   Platform routes sit under the `platform` guard, institution routes under `institution`.
3. Add a sidebar entry in `src/components/shell/nav.ts` if it is top-level.
4. Strings → `src/i18n/en.json` **and** `rw.json` (same keys; a test enforces parity).

Guards (`src/app/guards.ts`) are loaders: no session → full-page redirect to
`/bff/login?returnTo=<path>`; platform area needs `platformAdmin`; institution
area needs `institutionAdmin` (or a platform admin acting for one). They are
cosmetic: comments in code say so; the wrapper enforces.

## API client

```ts
import { api, unwrap, unwrapAs, apiFetch, downloadFile } from "@/api/client";

// typed path/params/body/response from the generated schema
const client = await unwrap(api.GET("/client/clients/{client_id}", { params: { path: { client_id: id } } }));
// endpoint whose response the contract leaves untyped → name the type
const mfa = await unwrapAs<RealmMfaState>(api.GET("/realm/mfa"));
// mutations: CSRF header added automatically
await unwrap(api.PUT("/client/clients/{client_id}/scope", { params: { path: { client_id: id } }, body: { ...fields, confirm } }));
// files
await downloadFile("/audit/export/csv?from_date=…", "audit.csv");
await apiFetch("/realm/branding/logo", { method: "POST", body: formData });
```

- Response types: `unwrap()` returns `Present<T>` — every key present, as the
  wrapper's Jackson `default-property-inclusion: always` writes them (springdoc
  marks nothing `required`). Values may still be null; guard optional ones.
  Request bodies use the generated (optional) types: omit, don't send `null`.
  Many wrapper responses are untyped maps — those keep hand-written types in
  `features/*/types.ts`, checked against the Java services.
- Base is `/api`; the BFF strips it. `credentials: "include"`. `X-XSRF-TOKEN`
  (from the `XSRF-TOKEN` cookie) on POST/PUT/PATCH/DELETE.
- Every failure throws `ApiError { status, code, message, details }` from the
  wrapper envelope `{ error, message, details }` (legacy `{ detail }` understood).
  409 confirmation flows read their numbers from `details` (e.g.
  `removed_institutions`, `access_at_risk`, `active_sessions`, `assigned_users`).
- A 401 triggers one `/bff/session` re-check; if the session is gone the browser
  goes to `/bff/login?returnTo=<current path>`. The SPA never sees a token.

## Query keys

Each feature owns its keys in `features/<feature>/api.ts`, rooted at the feature
name: `clientKeys.all = ["clients"]`, `clientKeys.detail(id) = ["clients","detail",id]`.
Invalidate the narrowest key after a mutation (`qc.invalidateQueries({ queryKey: clientKeys.detail(id) })`),
or the root to refresh everything of a kind. `["session"]` is the BFF session.
Reads use `queryOptions` factories (`clientsListQuery()`) so pages, tests and
loaders share one definition.

## UI conventions

- Never render a value you could not read as 0: use `formatCount(null) → "—"` and
  `<Unavailable error=… />` / `<ErrorState error=… />`.
- Confirm destructive actions with `const confirm = useConfirm()`; toasts via
  `notify.success / notify.error(e)`.
- Forms: react-hook-form + zod; zod schemas mirror the wrapper DTOs.
- Tables: `DataTable` (TanStack Table).
- Dates: `formatWhen()` (UTC, locale-independent).

## i18n

One namespace with nested keys grouped by feature (`common.*`, `nav.*`,
`dashboard.*`, `clients.*` …). `useTranslation()` → `t("clients.title")`.
Kinyarwanda strings reuse the login theme's translations where they exist;
the rest are drafted and need a native-speaker review before production.
`src/i18n/parts/<ns>.<lang>.json` files are deep-merged at runtime and folded
into `en.json`/`rw.json` with `node scripts/i18n-fold.mjs` (used while several
people add strings in parallel).

## Tests

- **Pure state modules** (`features/**/state/*.ts`) have exhaustive unit tests:
  `clients/state/mfaStatus` (mirrors `mfa-policy` MfaPolicy), `clients/state/clientScopeForm`
  (scope form + 409 confirm reducer), `users/state/clientAccessState` (per-client
  access states), `dashboard/state/attention`.
- **Component tests**: RTL + MSW. `renderPage(<Page />, { route, path })` from
  `@/test/render` wraps providers + a memory router and seeds a platform-admin
  session; `server.use(http.get(api("/client/clients"), …))` from `@/test/server`;
  `envelope(409, "code", "msg", details)` builds a wrapper error.
- **Playwright** (`e2e/`): `npm run build && npm run test:e2e`. The config
  starts `e2e/stub-server.mjs`, which serves `dist/` (SPA fallback) and stands
  in for the BFF (`/bff/session`, `/bff/login`, `/bff/logout`) and the wrapper
  (`/api/**`). Gate: `auth-guard.spec.ts`. If Chromium cannot run on the host,
  run inside `mcr.microsoft.com/playwright:v1.48.0-jammy` (see `e2e/README.md`).

## Institution area

`/institution/**` (delegated institution admins, and platform admins acting via
`?institution=<slug>`). `features/institution/`:

- `context.tsx` — `InstitutionProvider` resolves `GET /institution-admin/me` +
  `/permissions` once (in `components/InstitutionLayout.tsx`);
  `useInstitution()` → `{ ctx, perms, scope, qs, can(cap), path(p, extra) }`.
  Pass `scope` to every `/institution-admin/*` call and build links with
  `path()` so `?institution=` is kept.
- `components/InstitutionGate.tsx` — the 503 "delegated permissions not
  loaded" and 403/400 "no institution" screens; `CapabilityGate` names a
  missing capability. Sidebar entries are filtered by capability. All cosmetic:
  the wrapper enforces.
- Query keys: `institutionKeys.scoped(scope, "<area>", ...)` →
  `["institution", slug, ...]`, so switching institution never shows another's cache.
- Sub-areas: `overview/`, `staff/`, `audit/`, `apps/`, `employees/` (selection
  + provisioning), `access/` (matrix, import, revoke); pages in `pages/`.
- Tests: `renderInstitutionPage(<Page/>, { capabilities })` from
  `@/test/institution` answers `/me` + `/permissions` via MSW.
