# Integrating an application with GoRSSO (Spring Boot + React)

How a ministry or agency connects a **Spring Boot API** and a **React SPA** to the Government of
Rwanda Single Sign-On (GoRSSO / SSO.RISA), using the platform's
[`sso-spring-boot-starter`](../../sso-spring-boot-starter) and
[`@gorsso/react`](../../sdk/react).

Worked example throughout: the demo ministries in [`sample-apps/`](../../sample-apps): MINAGRI's
`kubaka-web` (client `minagri-kubaka-web`) and MINISANTE's `hmis` (client `minisante-hmis`).

This guide replaces the reference `examples/SSO_INTEGRATION_GUIDE.md` (Python/Node/Java over raw
HTTP) and `docs/SSO_INTEGRATION_SPRINGBOOT_ANGULAR.md` (Spring Boot + Angular). The protocol rules
are unchanged; the starter and the SDK now implement them for you.

---

## Contents

0. [Rules every integration must follow](#0-rules-every-integration-must-follow)
1. [How the platform is shaped](#1-how-the-platform-is-shaped)
2. [Register your application](#2-register-your-application)
3. [Choose an architecture](#3-choose-an-architecture)
4. [Backend: Spring Boot API with the starter](#4-backend-spring-boot-api-with-the-starter)
5. [Frontend: React with `@gorsso/react`](#5-frontend-react-with-gorssoreact)
6. [The join key](#6-the-join-key)
7. [Roles](#7-roles)
8. [Logout](#8-logout)
9. [Docker and networking](#9-docker-and-networking)
10. [Endpoint reference](#10-endpoint-reference)
11. [Run the sample apps](#11-run-the-sample-apps)
12. [Troubleshooting](#12-troubleshooting)
13. [New-app checklist](#13-new-app-checklist)

---

## 0. Rules every integration must follow

These rules come from the wrapper's OIDC gateway. A client that breaks one gets an error.

1. **PKCE (RFC 7636) is required, and only `S256` is accepted.** Send `code_challenge` and
   `code_challenge_method=S256` to `/oauth2/authorize`, then the matching `code_verifier` to
   `/oauth2/token`. The wrapper rejects `plain`. This applies to confidential clients as well.
2. **Only `response_type=code` is accepted.** The wrapper rejects the implicit and hybrid flows.
3. **Refresh tokens are single-use, because they rotate.** Every refresh returns a new
   `refresh_token`, and you must keep the new one. Sending an old refresh token again gets 400
   `invalid_grant`.
4. **`redirect_uri` and `post_logout_redirect_uri` must be registered.** They must match the
   client's registered URIs exactly (Keycloak's `/*` suffix pattern is allowed). If they don't,
   you get 400.
5. **Always send `client_id` to `/oauth2/logout`.** The wrapper rejects
   `post_logout_redirect_uri` when it comes without `client_id`.
6. **Confidential clients must send their secret** (form body or HTTP Basic). The wrapper never
   looks a secret up for you. Public clients (SPAs in PKCE mode) have no secret.
7. **Three gates guard every token.** They run on `authorization_code` **and** `refresh_token` at
   `/oauth2/token`. Your app must expect these errors and show them to the user:

   | HTTP | `error` | Meaning | What the user should do |
   |---|---|---|---|
   | 403 | `access_denied` | The person has not been granted this app (per-client access) | Ask the institution admin for access |
   | 403 | `join_key_missing` / `join_key_invalid` / `join_key_not_unique` | The person's NID/phone/email is unusable as this app's join key | Ask the admin to fix the profile |
   | 403 | `mfa_required` | TOTP applies to this client (password grant only) | Use the browser flow |

8. **Rate limits** (per IP): `/oauth2/authorize` 60/min, `/oauth2/token` 120/min,
   `/oauth2/logout` 60/min. Requests above the limit get 429.
9. **Level of assurance:** send `acr_values=2` to ask for password plus TOTP. The token's `acr`
   claim reports what was achieved; check for `acr == "2"`.

---

## 1. How the platform is shaped

GoRSSO is **not** a plain Keycloak. Two different hosts serve the protocol:

| Concern | Served by | Example (local dev) |
|---|---|---|
| Token **issuer** (`iss`) | Keycloak | `http://localhost:8080/realms/government-internal` |
| **JWKS** (signing keys) | Keycloak | `…/realms/government-internal/protocol/openid-connect/certs` |
| **authorize** / **token** / **logout** / **introspect** | Wrapper | `http://localhost:8000/oauth2/*` |
| **userinfo** (enriched) | Wrapper | `http://localhost:8000/auth/userinfo` |

This has three consequences:

- **Don't configure the issuer from discovery.** The wrapper's
  `/oauth2/.well-known/openid-configuration` advertises the Keycloak issuer, which does not
  match the host you fetched it from. So Spring's `issuer-uri` auto-discovery fails. The starter
  instead takes the issuer (only *compared* with `iss`) and the JWKS URL (actually *fetched*)
  as two separate settings.
- **Tokens are RS256 Keycloak tokens.** They carry `azp` (the client the token was issued to),
  `realm_access.roles`, `resource_access.<client>.roles`, `preferred_username`, `institution`
  and `join_key`. The `aud` claim is usually `account`, so **check `azp`, not `aud`**. The
  starter does this.
- **The browser talks to the wrapper; Keycloak shows the login page.** The wrapper checks the
  client, the redirect URI and PKCE, then sends the browser to Keycloak.

Realms: civil servants are in `government-internal`, which is the realm for every ministry app.
Each institution is a group keyed by a lowercase **slug** (`minagri`, `minisante`).

---

## 2. Register your application

Applications are **onboarded as code**, not created by clicking in the Keycloak admin console.
Add your app under your institution in `configs/institutions.yaml`, and the Terraform in
`infra/terraform` creates the client:

```yaml
- slug: minagri
  display_name: Ministry of Agriculture and Animal Resources
  apps:
    - name: kubaka-web              # client id becomes  minagri-kubaka-web
      display_name: Kubaka
      roles: [viewer, editor, approver]   # client roles
      public: true                  # SPA doing PKCE itself (no secret); omit/false for a BFF
      redirect_uris:
        - https://kubaka.minagri.gov.rw/callback
      web_origins: ["+"]
```

- The client id is always `<slug>-<app>`.
- List only environment-independent URIs here. Each environment adds its own redirect URIs
  through `extra_redirect_uris` in its tfvars (dev allows `http://localhost:*/*`).
- The post-logout redirect URI must also be registered on the client
  (`post.logout.redirect.uris`).
- Institution admins can also create an app in the console at `/institution/apps/new`. The
  console fixes the slug prefix.
- Set the app's **join column** (`nid` | `phone` | `email`) in the console at
  `/institution/apps/:clientId/join-key`. See [§6](#6-the-join-key).
- **Access:** by default only members of the owning institution can sign in. Grant people access
  in the console's access matrix (`/institution/access/matrix`).

You will need these values:

| Value | Example |
|---|---|
| Wrapper URL (browser-facing) | `http://localhost:8000` / `https://sso.gov.rw` |
| Issuer | `http://localhost:8080/realms/government-internal` |
| JWKS URL reachable from your API | `http://keycloak:8080/realms/government-internal/protocol/openid-connect/certs` |
| Client id | `minagri-kubaka-web` |
| Client secret | (confidential/BFF clients only; from the console's secret reveal) |

---

## 3. Choose an architecture

| | **A. SPA + API, PKCE in the browser** | **B. BFF (backend-for-frontend)** |
|---|---|---|
| Client type | public | confidential |
| Who holds tokens | the SPA, in memory only | the BFF, in a server-side session |
| SPA library | `@gorsso/react`, `mode: "pkce"` | `@gorsso/react`, `mode: "bff"` |
| API protection | `sso-spring-boot-starter` (bearer) | `sso-spring-boot-starter` (bearer, sent by the BFF), or the BFF itself |
| Use for | simple apps, demos (the samples) | **anything with personal or sensitive data** (the GoRSSO console uses this) |

Use **B** whenever you can: tokens never reach the browser, and refresh and logout happen on the
server. **A** is acceptable for low-risk apps; `@gorsso/react` keeps it safe (memory-only tokens,
state/nonce checks, refresh-token rotation).

---

## 4. Backend: Spring Boot API with the starter

### 4.1 Dependency

```xml
<dependency>
  <groupId>rw.gov.risa.sso</groupId>
  <artifactId>sso-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

The starter needs Spring Boot 3.5 and Java 21. It brings in
`spring-boot-starter-oauth2-resource-server`.

### 4.2 Configuration

```yaml
gorsso:
  issuer-uri: ${GORSSO_ISSUER_URI:http://localhost:8080/realms/government-internal}   # compared with iss
  jwk-set-uri: ${GORSSO_JWK_SET_URI:}            # fetched; empty = <issuer>/protocol/openid-connect/certs
  client-id: minagri-kubaka-web                  # accepted azp + whose client roles become ROLE_*
  # allowed-azp: [minagri-kubaka-mobile]         # other clients whose tokens this API accepts
  institution:
    slug: minagri                                # the app's own institution (InstitutionRoles.slug())
  join-key:
    column: nid                                  # what join_key IS for this app: nid | phone | email
  resource-server:
    permit-all: [/actuator/health, /actuator/health/**, /api/public/**, /error]
```

What you get, with no code:

- A `JwtDecoder` that accepts **only RS256** tokens. It checks the signature against the JWKS,
  `exp`/`nbf` (5 s skew, `gorsso.clock-skew`), `iss` = `issuer-uri`, and `azp` ∈ `client-id` +
  `allowed-azp`. A token that fails any check gets 401.
- A stateless security chain: the `permit-all` paths are open and everything else needs a valid
  bearer. You only get this chain if your app does not declare a `SecurityFilterChain` of its own.
- Authorities:
  - `inst:<slug>:<role>` realm roles → `INST_<slug>_<role>` (for example `INST_minagri_staff`)
  - `resource_access.<client-id>.roles` → `ROLE_<role>` (for example `ROLE_editor`, which
    `hasRole('editor')` checks)
  - `scope` → `SCOPE_<scope>`
- `Authentication.getName()` returns `preferred_username`.
- `@JoinKey` and `InstitutionRoles` controller arguments, and a SpEL bean named `gorsso`.

**Opt-outs.** Every bean backs off if you declare your own. The switches are:
`gorsso.enabled=false` (everything), `gorsso.resource-server.enabled=false` (decoder + chain),
`gorsso.resource-server.default-chain=false` (chain only),
`gorsso.roles.{institution,client,scopes}.enabled=false`, and `gorsso.join-key.enabled=false`.

### 4.3 Controllers

```java
@SpringBootApplication
@EnableMethodSecurity            // the starter does not enable method security for you
public class KubakaApi { ... }

@RestController
@RequestMapping("/api")
class RecordsController {

  @GetMapping("/me")
  Me me(JwtAuthenticationToken auth, @JoinKey String joinKey, InstitutionRoles inst) {
    // joinKey: the caller's NID / phone / email (per gorsso.join-key.column), normalised
    // inst.slug() = "minagri"; inst.roles() = {"staff", ...}; inst.hasRole("staff")
    ...
  }

  @GetMapping("/records")
  @PreAuthorize("@gorsso.memberOfInstitution()")          // any inst:minagri:* role
  List<Record> records() { ... }

  @PostMapping("/records")
  @PreAuthorize("hasRole('editor')")                      // client role on minagri-kubaka-web
  Record create(@RequestBody Record r) { ... }

  @GetMapping("/hr")
  @PreAuthorize("hasAuthority('INST_minagri_hr')")        // one institution role
  ... 
}
```

- `@JoinKey(required = false)` binds `null` when the claim is missing. With the default
  (`required = true`) a missing claim answers 403 `{"error":"join_key_missing", …}`.
- `@JoinKey JoinKeyValue jk` gives you `jk.column()` and `jk.value()` together.
- SpEL helpers: `@gorsso.memberOfInstitution()`, `@gorsso.memberOf('minagri')`,
  `@gorsso.institutionRole('viewer')`, `@gorsso.institutionRole('inst:minagri:*')`, and
  `@gorsso.hasJoinKey()`.
- Outside controllers, inject `JoinKeyResolver` (`current()`, `require()`) or
  `InstitutionRolesResolver` (`current()`).

### 4.4 Your own security chain

If you declare a `SecurityFilterChain`, the starter's default chain steps aside. Reuse its
converter so you keep the authorities:

```java
@Bean
SecurityFilterChain api(HttpSecurity http, GorssoJwtAuthenticationConverter gorsso) throws Exception {
  return http
      .authorizeHttpRequests(a -> a.requestMatchers("/api/public/**").permitAll().anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(gorsso)))
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .csrf(c -> c.disable())
      .build();
}
```

If you build your own `JwtDecoder`, keep the platform checks:
`decoder.setJwtValidator(GorssoJwtDecoders.validator(props))`.

### 4.5 Testing

```java
@SpringBootTest @AutoConfigureMockMvc
class ApiTest {
  @Autowired MockMvc mvc;
  @Autowired GorssoAuthoritiesConverter authorities;   // the app's real mapping

  @Test void recordsNeedAnInstitutionRole() throws Exception {
    mvc.perform(get("/api/records").with(jwt()
            .jwt(j -> j.claim("preferred_username", "jdoe")
                       .claim("join_key", "1199080012345678")
                       .claim("realm_access", Map.of("roles", List.of("inst:minagri:staff")))
                       .claim("resource_access", Map.of("minagri-kubaka-web", Map.of("roles", List.of("viewer")))))
            .authorities(authorities)))
        .andExpect(status().isOk());
  }
}
```

Pass `.authorities(authorities)`, otherwise `jwt()` only maps `scope`. Note that `jwt()` names
the principal after `sub`. Read `preferred_username` from the token if you need the username in
a test.

### 4.6 Architecture B: the BFF is a confidential client

A Spring BFF signs users in with `spring-boot-starter-oauth2-client` (as the reference Angular
guide did), and then calls your API with the user's access token. Two things matter.

**1. Register the client by hand, not from the issuer location**, so the issuer and the
endpoints can differ:

```java
ClientRegistration.withRegistrationId("gorsso")
    .clientId(clientId).clientSecret(clientSecret)
    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
    .scope("openid", "profile", "email")
    .issuerUri(issuer)                          // compared only
    .jwkSetUri(jwks)                            // fetched
    .authorizationUri(wrapper + "/oauth2/authorize")   // browser-facing
    .tokenUri(wrapperInternal + "/oauth2/token")       // server-side
    .userInfoUri(wrapperInternal + "/auth/userinfo")
    .userNameAttributeName("preferred_username")
    .build();
```

**2. Turn PKCE back on.** Spring leaves it off for confidential clients by default, but the
wrapper requires it. Keep the session `IF_REQUIRED`, and answer API calls with 401 rather than
a redirect:

```java
var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
http.oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver)))
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
    .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PathPatternRequestMatcher.withDefaults().matcher("/bff/**")));
```

Then expose the three endpoints that `@gorsso/react` expects in `bff` mode:

- `GET /bff/session` returns
  `{authenticated, username, name, roles, joinKey, institutionSlug, …}`, or 401.
- `GET /bff/login?returnTo=/path` starts the login. Accept only same-origin paths for `returnTo`.
- `POST /bff/logout` is CSRF-protected (Spring's `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header).
  It answers `{ "logoutUrl": "<wrapper>/oauth2/logout?client_id=…&id_token_hint=…&post_logout_redirect_uri=…" }`.

The GoRSSO console's `console-bff` is a reference implementation of this contract.

---

## 5. Frontend: React with `@gorsso/react`

### 5.1 Install

```json
"dependencies": { "@gorsso/react": "1.0.0", "react": "18.3.1", "react-router-dom": "6.30.1" }
```

Inside this repo, the sample uses `"@gorsso/react": "file:../../sdk/react"`. Build the SDK first
(`cd sdk/react && npm ci && npm run build`), and add `resolve.dedupe: ["react", "react-dom"]` to
the Vite config so there is only one copy of React.

### 5.2 Provider and routes (architecture A, PKCE)

```tsx
// main.tsx
<BrowserRouter>
  <GorssoProvider config={{
    mode: "pkce",
    baseUrl: import.meta.env.VITE_GORSSO_BASE_URL,         // http://localhost:8000
    clientId: import.meta.env.VITE_CLIENT_ID,              // minagri-kubaka-web
    redirectUri: import.meta.env.VITE_REDIRECT_URI,        // http://localhost:5173/callback
    postLogoutRedirectUri: import.meta.env.VITE_POST_LOGOUT_REDIRECT_URI,
    scopes: ["openid", "profile", "email"],
    // acrValues: "2",                                     // require TOTP
  }}>
    <App />
  </GorssoProvider>
</BrowserRouter>

// App.tsx
<Routes>
  <Route path="/" element={<Home />} />
  <Route path="/callback" element={<Callback />} />          {/* the provider does the exchange */}
  <Route element={<RequireAuth fallback={<Spinner />}><Outlet /></RequireAuth>}>
    <Route path="/me" element={<Me />} />
  </Route>
  <Route path="/records" element={
    <RequireRole role="inst:minagri:*" forbidden={<Forbidden />}><Records /></RequireRole>} />
</Routes>
```

What the provider does:

- **Login:** `useAuth().login(returnTo)` creates a `code_verifier`, the S256 challenge, `state`
  and `nonce`. It keeps them in a single-use `sessionStorage` transaction with a 10-minute TTL,
  then redirects to `/oauth2/authorize`.
- **Callback:** on `/callback?code&state`, the provider checks `state` (unknown, reused or
  expired → `state_mismatch`). It POSTs to `/oauth2/token` with the `code_verifier` and no
  secret. It checks the id_token's `nonce`, `aud`, `exp` and optionally `iss`, and keeps the
  tokens **in memory only**. Finally it replaces the URL with `returnTo` (React Router picks
  this up).
- **Refresh:** the provider refreshes before expiry and rotates the refresh token. If the
  refresh fails, the user is signed out (`status: "error"`, `error.code` such as `invalid_grant`).
- **Reload:** the tokens are gone, so `<RequireAuth>` logs in again. While the Keycloak SSO
  session lives this is instant and the user sees no form.

Useful hooks:

```tsx
const { status, isAuthenticated, session, error, login, logout, hasRole } = useAuth();
const { session } = useSession();     // {username, name, email, roles, clientRoles, joinKey, institutionSlug}
const joinKey = useJoinKey();
const authFetch = useAuthFetch();     // adds a fresh bearer token (pkce) / cookies (bff)
const res = await authFetch("/api/records");
```

Show `error.code` on the callback page. The values to expect are `access_denied`,
`join_key_*`, `state_mismatch` and `nonce_mismatch`; see [§0 rule 7](#0-rules-every-integration-must-follow).

### 5.3 Architecture B (BFF)

```tsx
<GorssoProvider config={{ mode: "bff", bffBase: "" }}>   {/* same origin */}
```

`useSession()` reads `GET /bff/session`. `login()` goes to `/bff/login?returnTo=…`, and
`logout()` POSTs to `/bff/logout`. `useAuthFetch()` sends cookies. The guards and hooks behave
the same as in PKCE mode.

### 5.4 Guards are cosmetic

`<RequireAuth>` and `<RequireRole>` only decide what to *render*. Your API must enforce the same
rule (§4.3). Role forms are `inst:<slug>:<role>`, `inst:<slug>:*`, `client:<role>`, or any realm
role.

---

## 6. The join key

Your app has its own user table. The **join key** is how a GoRSSO identity is matched to a row
in that table.

- Each client chooses a **join column**: `nid` (national ID), `phone`, or `email`. If no column
  is set, the client behaves as `email`.
- A single protocol mapper puts the value in the access token as the claim **`join_key`**,
  whatever the column is. The claim does not say which column it came from. Your app knows its
  own column: set `gorsso.join-key.column` to match.
- The wrapper refuses a token whose join key is missing, malformed or not unique (403
  `join_key_*`). So in a correctly configured app, `@JoinKey` is always present.
- The starter normalises the value: e-mail is lower-cased, NID and phone are trimmed. Match on
  the normalised value:

```java
@GetMapping("/api/me")
Profile me(@JoinKey String nid) {
  return farmers.findByNationalId(nid).orElseThrow(NotRegisteredHere::new);
}
```

- `JoinKeyValue.toString()` never prints the value. Treat NIDs and phone numbers as personal
  data, and don't log them.
- Changing a live app's column re-keys every user, so the console asks for confirmation.
  Coordinate the change with your data migration.

---

## 7. Roles

| Source in the token | Example | Spring authority | React |
|---|---|---|---|
| Realm role `inst:<slug>:<role>` (institution membership/tier) | `inst:minagri:staff` | `INST_minagri_staff` | `hasRole("inst:minagri:staff")`, `inst:minagri:*` |
| Client role `resource_access.<client>.roles` (what the person may do in *your* app) | `editor` on `minagri-kubaka-web` | `ROLE_editor` | `hasRole("client:editor")` |
| Platform tiers `super_admin`/`admin`/`system_admin` | | (not mapped) | realm role |

The institution tiers come from `configs/delegated-permissions/templates.json` and are
`admin`, `hr`, `auditor` and `staff`. Define your app's own roles as **client roles** in
`institutions.yaml`, and let the institution admin assign them.

---

## 8. Logout

A local logout (clearing the SPA's memory) leaves the Keycloak SSO session alive, so the user is
signed straight back in. Use **RP-initiated logout**:

```
GET {wrapper}/oauth2/logout?client_id=minagri-kubaka-web
    &post_logout_redirect_uri=http://localhost:5173/
    &id_token_hint=<id_token>
```

`useAuth().logout()` does this for you. It clears memory, then navigates with `client_id`
always set, and with `id_token_hint` and `post_logout_redirect_uri`. Register the
post-logout URI on the client exactly. In BFF mode the BFF builds this URL.

---

## 9. Docker and networking

Inside a container, `localhost` refers to the container itself. Split the URLs by who uses them:

| URL | Used by | In Docker |
|---|---|---|
| `GORSSO_ISSUER_URI` | compared with `iss` | keep the **browser-facing** value (`http://localhost:8080/realms/…`) |
| `GORSSO_JWK_SET_URI` | API fetches keys | a **reachable** host: `http://keycloak:8080/…/certs`, or `http://host.docker.internal:8080/…` plus `extra_hosts: ["host.docker.internal:host-gateway"]` |
| `VITE_GORSSO_BASE_URL` | browser | browser-facing wrapper URL |
| BFF token/userinfo URIs | server | an internal wrapper URL |

The SPA image serves the static bundle and proxies `/api/` to `$API_UPSTREAM`, so the browser
makes same-origin calls and needs no CORS on your API. The browser does call the wrapper's
`/oauth2/token` directly in PKCE mode, so add your SPA origin to the wrapper's CORS allow-list.
The allow-list never accepts `*`.

Never bake client secrets into images. Pass them as `<NAME>_FILE` Docker secrets or through
environment variables from a gitignored `.env`.

---

## 10. Endpoint reference

| Method | Path (wrapper) | Notes |
|---|---|---|
| GET | `/oauth2/authorize` | `response_type=code`, `client_id`, `redirect_uri` (exact), `scope` (default `openid profile email`), `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`, optional `acr_values`. Answers 302 to Keycloak. |
| POST | `/oauth2/token` | form; `authorization_code` (`code`, `redirect_uri`, `client_id`, `code_verifier`, + `client_secret` for confidential clients), `refresh_token` (rotating), `client_credentials`. 403 for the gates in §0. |
| GET | `/oauth2/logout` | `client_id` (required with `post_logout_redirect_uri`), `post_logout_redirect_uri`, `id_token_hint`, `state`. |
| GET | `/oauth2/.well-known/openid-configuration` | discovery (issuer is Keycloak's; see §1) |
| GET | `/oauth2/jwks` | the realm's signing keys |
| POST | `/oauth2/introspect` | RFC 7662; `token`, `client_id`, `client_secret` |
| GET | `/auth/userinfo` | enriched user info (bearer) |
| GET | `/auth/verify` | `{valid}`; fails closed |

Typical token response:

```json
{ "access_token": "eyJ…", "refresh_token": "eyJ…", "id_token": "eyJ…",
  "token_type": "Bearer", "expires_in": 300, "refresh_expires_in": 1800 }
```

---

## 11. Run the sample apps

Prerequisites: the GoRSSO stack (Keycloak, wrapper) is running locally, with
`minagri-kubaka-web` / `minisante-hmis` registered as **public** clients whose redirect URIs are
`http://localhost:5173/callback` / `http://localhost:5174/callback`. The user also needs an
`inst:minagri:*` / `inst:minisante:*` role.

```bash
# from the repo root
mvn -q -B -N install
mvn -q -B -f sso-spring-boot-starter/pom.xml install
mvn -q -B -f sample-apps/api/pom.xml verify

SPRING_PROFILES_ACTIVE=minagri   java -jar sample-apps/api/target/gorsso-sample-api.jar   # :8081
SPRING_PROFILES_ACTIVE=minisante java -jar sample-apps/api/target/gorsso-sample-api.jar   # :8082

export PATH=$PWD/.tools/node/bin:$PATH
(cd sdk/react && npm ci && npm run build)
cd sample-apps/web && npm ci
npm run dev:minagri      # http://localhost:5173  (proxies /api → :8081)
npm run dev:minisante    # http://localhost:5174  (proxies /api → :8082)
```

Docker (build context = repo root):

```bash
docker build -f sample-apps/api/Dockerfile -t gorsso/sample-api .
docker build -f sample-apps/web/Dockerfile --build-arg MODE=minagri -t gorsso/sample-web:minagri .
docker network create gorsso-demo
docker run -d --name api-minagri --network gorsso-demo -e SPRING_PROFILES_ACTIVE=minagri \
  -e GORSSO_JWK_SET_URI=http://host.docker.internal:8080/realms/government-internal/protocol/openid-connect/certs \
  --add-host host.docker.internal:host-gateway gorsso/sample-api
docker run -d --network gorsso-demo -p 5173:8080 -e API_UPSTREAM=http://api-minagri:8080 gorsso/sample-web:minagri
```

What to try:

- `/` works anonymously and calls `GET /api/public/info`.
- `/me` logs you in through the wrapper, then shows the token's view next to `GET /api/me`
  (`join_key` and its column, institution roles, client roles).
- `/records` shows data for `inst:minagri:*`. A MINISANTE user sees the 403 page, and the API
  answers 403 as well.

---

## 12. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| 400 `code_challenge` required / `Unsupported code_challenge_method` | No PKCE, or `plain` | Use S256. In a Spring BFF, add `OAuth2AuthorizationRequestCustomizers.withPkce()` (§4.6) |
| 400 on `/oauth2/authorize`: redirect URI not allowed | `redirect_uri` is not registered exactly (`localhost` vs `127.0.0.1`, trailing slash, port) | Register the exact URI (dev tfvars allow `http://localhost:*/*`) |
| 400 on `/oauth2/token` for the SPA: client authentication failed | The client is confidential, but the SPA sends no secret | Make the client `public: true`, or use a BFF |
| CORS error on `/oauth2/token` | SPA origin missing from the wrapper's allow-list | Add the origin to the wrapper CORS config |
| API answers 401 to a valid-looking token | `iss` ≠ `gorsso.issuer-uri` (the issuer is the browser-facing Keycloak URL), `azp` not in `client-id`/`allowed-azp`, an unreachable JWKS, or not RS256 | Check the token at jwt.io against §4.2. Turn on `logging.level.org.springframework.security=DEBUG` |
| API answers 403 on `/records` | No `inst:<slug>:*` role, or `gorsso.institution.slug` is wrong | Assign the role in the console. Check the profile |
| 403 `join_key_missing` from the gateway or the API | The person has no value for the app's join column | Fix the profile, or the app's join column (`/institution/apps/join-keys`) |
| 403 `access_denied` at `/oauth2/token` | The person has no access to this client | Grant it in the access matrix |
| SPA callback shows `state_mismatch` | Callback opened in another tab, page refreshed after use, or the transaction is older than 10 min | Start the login again. Don't bookmark `/callback` |
| `nonce_mismatch` / `invalid_id_token` | Replayed or injected response, or the wrong client id | Start the login again. Check `clientId` |
| Signed out after about 5 minutes | Refresh failed: an old refresh token was replayed (rotation), or the SSO session ended | Use one client instance per tab (the SDK does). Check the realm session lifetimes |
| `Invalid hook call` in the SPA | Two copies of React (linked SDK) | `resolve.dedupe: ["react","react-dom"]` |
| Logout leaves you signed in | Local logout only | Use `useAuth().logout()` (wrapper `/oauth2/logout` with `id_token_hint`) |
| 400 `client_id is required when post_logout_redirect_uri is provided` | Hand-built logout URL | Add `client_id` |
| Container cannot reach `localhost:8080` | `localhost` is the container | `host.docker.internal` + `extra_hosts`, or the service name (§9) |
| 429 | Rate limit (§0) | Back off. Don't poll `/oauth2/token` |

---

## 13. New-app checklist

- [ ] App added to `configs/institutions.yaml` (`<slug>-<app>`, client roles, `public` for PKCE
      SPAs), redirect + post-logout URIs registered per environment
- [ ] Join column set for the client. The API's `gorsso.join-key.column` matches it
- [ ] API: `sso-spring-boot-starter` dependency, `gorsso.issuer-uri` / `jwk-set-uri` /
      `client-id` / `institution.slug`, `@EnableMethodSecurity`, every endpoint guarded
      (`@gorsso.memberOfInstitution()`, `hasRole(...)`)
- [ ] API tests with `jwt().authorities(gorssoAuthoritiesConverter)`: 401 anonymous, 403 for
      another institution, 200 for a member
- [ ] SPA: `GorssoProvider` (`pkce` with a public client, or `bff`), `/callback` route that shows
      `error.code`, `RequireAuth`/`RequireRole` on routes, `useAuthFetch` for API calls
- [ ] Logout through `useAuth().logout()`. Post-logout URI registered
- [ ] SPA origin in the wrapper CORS allow-list (PKCE mode)
- [ ] Docker: issuer compared, JWKS reachable, no secrets in images
- [ ] Tried the gate errors: a user without access (`access_denied`) and a user without a join
      key (`join_key_missing`)
