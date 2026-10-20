# GoRSSO SDKs

| Path | What | Committed? |
|---|---|---|
| `sdk/generate.sh` | Generates the client SDKs from the wrapper's OpenAPI contract (openapi-generator in Docker) | yes |
| `sdk/config/*.yaml` | Generator options per target (ported from the reference `configs/sdk/*`) | yes |
| `sdk/java/` | Generated Java 21 client (`rw.gov.risa.sso:gorsso-java-sdk`, `java.net.http` + Jackson) | **no** (gitignored, CI regenerates) |
| `sdk/typescript/` | Generated TypeScript client (`@gorsso/client`, `typescript-fetch`) | **no** |
| `sdk/python/` | Optional generated Python client (`sdk/generate.sh python`) | **no** |
| `sdk/react/` | Hand-written React SDK `@gorsso/react` (login, session, guards) | yes |

The **generated** SDKs wrap the wrapper's *management* API (clients, users, institution admin,
access matrix, …). Signing a user in is standard OIDC and does not need them: use
`@gorsso/react` in the browser and `sso-spring-boot-starter` in a Spring Boot API.

## Generated clients (Java, TypeScript)

Requirements: Docker. The generator image is pinned (`openapitools/openapi-generator-cli:v7.14.0`)
so every machine and CI produce the same output for the same spec.

```bash
sdk/generate.sh                         # java + typescript from sso-wrapper/openapi.json
sdk/generate.sh java                    # one target: java | typescript | python
SPEC=/path/to/openapi.json sdk/generate.sh
VERIFY=1 sdk/generate.sh                # also compile: mvn package (java), tsc (typescript)
GENERATOR_IMAGE=openapitools/openapi-generator-cli:v7.14.0 sdk/generate.sh   # override the pin
```

The script validates the spec first, wipes `sdk/<target>/`, generates with
`sdk/config/<target>.yaml`, and runs the container as your uid (no root-owned files).

The contract is `sso-wrapper/openapi.json`, kept in sync by the wrapper's
`OpenApiContractTest` (`-Dopenapi.update=true` rewrites it). **Do not commit generated output** —
CI runs `VERIFY=1 sdk/generate.sh` on every change to the contract and publishes the artefacts.

Verified on 2026-10-20 against the reference contract
(`/home/imfura/Data/CURIE_RISA/sso/codebase/sso/openapi.json`): spec validates, Java (496 files)
`mvn package` passes, TypeScript (168 files) `tsc --noEmit` passes. The in-repo contract (still
being filled in) also generates.

### Using the Java client

```xml
<dependency>
  <groupId>rw.gov.risa.sso</groupId>
  <artifactId>gorsso-java-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
ApiClient api = new ApiClient();
api.updateBaseUri("https://sso.gov.rw");
api.setRequestInterceptor(b -> b.header("Authorization", "Bearer " + accessToken));
// then new <Tag>Api(api).<operation>(...)
```

### Using the TypeScript client

```ts
import { Configuration } from "@gorsso/client";
const config = new Configuration({ basePath: "https://sso.gov.rw", accessToken: async () => token });
// new <Tag>Api(config).<operation>({...})
```

## React SDK — `@gorsso/react`

Source: `sdk/react/` (TypeScript, Vite library mode, Vitest). React 18 peer dependency; works with
React Router v6 but does not depend on it.

```bash
export PATH=$PWD/.tools/node/bin:$PATH     # from the repo root
cd sdk/react
npm ci
npm test            # vitest
npx tsc --noEmit
npm run build       # dist/: ESM + CJS + .d.ts
```

### Two modes

| | `mode: "pkce"` | `mode: "bff"` |
|---|---|---|
| Who holds tokens | the SPA, **in memory only** | the BFF (server-side session) |
| Login | `GET {baseUrl}/oauth2/authorize` (code + PKCE **S256**, `state`, `nonce`) | `GET {bffBase}/bff/login?returnTo=` |
| Code exchange | `POST {baseUrl}/oauth2/token` (public client, `code_verifier`, no secret) | the BFF |
| Session | derived from the tokens | `GET {bffBase}/bff/session` (401 = anonymous) |
| Refresh | rotating refresh token, before expiry | the BFF |
| Logout | `GET {baseUrl}/oauth2/logout?client_id&post_logout_redirect_uri&id_token_hint` | `POST {bffBase}/bff/logout` (+ `X-XSRF-TOKEN`) |
| API calls | `Authorization: Bearer` (`useAuthFetch`) | cookies (`credentials: "include"`) |

Use `bff` for anything handling sensitive data (the console does); `pkce` suits simple SPAs and
demos. In `pkce` mode the client must be **public** in Keycloak.

```tsx
<BrowserRouter>
  <GorssoProvider config={{
    mode: "pkce",
    baseUrl: "http://localhost:8000",               // the wrapper, browser-facing
    clientId: "minagri-kubaka-web",
    redirectUri: "http://localhost:5173/callback",  // registered exactly
    scopes: ["openid", "profile", "email"],
  }}>
    <Routes>
      <Route path="/callback" element={<Callback />} />
      <Route element={<RequireAuth><Outlet /></RequireAuth>}>
        <Route path="/me" element={<Me />} />
      </Route>
      <Route path="/records" element={
        <RequireRole role="inst:minagri:*" forbidden={<Forbidden />}><Records /></RequireRole>} />
    </Routes>
  </GorssoProvider>
</BrowserRouter>
```

API:

- `GorssoProvider` — `config: GorssoConfig` (or an injected `client` for tests).
- `useAuth()` — `{ status, isAuthenticated, session, error, login(returnTo?), logout(),
  getAccessToken(), hasRole(role), hasAnyRole(roles) }`.
- `useSession()` — the session in the BFF's shape (`authenticated, username, name, email, roles,
  clientRoles, joinKey, institutionSlug, …extras`) in either mode.
- `useJoinKey()` — the `join_key` claim (nid | phone | email value, per the client's join column).
- `useAuthFetch()` — `fetch` with a fresh bearer (pkce) or cookies (bff).
- `<RequireAuth fallback? redirect? returnTo?>` — renders children for a signed-in user, otherwise
  starts login. Use `element={<RequireAuth><Outlet/></RequireAuth>}` for a layout route.
- `<RequireRole role="inst:minagri:viewer" | anyOf={[...]} forbidden?>` — role forms:
  `inst:<slug>:<role>`, `inst:<slug>:*` (any role in the institution), `client:<role>`
  (`resource_access.<clientId>.roles`), or any realm role. **Cosmetic** — the API must enforce it.
- Low level: `PkceAuthClient`, `BffAuthClient`, `codeChallengeS256`, `generateCodeVerifier`,
  `buildAuthorizeUrl`, `buildLogoutUrl`, `validateIdToken`, `MemoryTransactionStore`, …

Security notes:

- Tokens are never written to `localStorage`/`sessionStorage`. A reload means a new login round
  trip (silent while the Keycloak SSO session lives).
- Only the login *transaction* (`state` → `code_verifier`, `nonce`, `returnTo`) goes to
  `sessionStorage`, because it must survive the redirect: per tab, single use, 10-minute TTL.
- The callback is refused unless its `state` was issued by this tab and not yet used; the
  id_token's `nonce` and `aud` must match. The id_token comes straight from the token endpoint
  over TLS, so its signature is not re-checked in the browser (OIDC Core §3.1.3.7); the API
  verifies every access token's signature.
- `returnTo` must be a same-origin path (no open redirects).
- Gateway refusals surface as `error.code` (`access_denied`, `join_key_missing|invalid|not_unique`,
  `mfa_required`, `invalid_grant`, …).
