# console-bff: BFF for the React console (spec §1 Session, §2, §6 shell, §7)

The console-bff is a Spring Boot 3.5 service on **:3001**. It serves the built console (`console-web/dist`) and `/api/**`, using the backend-for-frontend (BFF) pattern:

- The SPA never sees a token.
- The BFF logs the user in through the **wrapper's** OIDC gateway as the confidential client `sso-admin-ui`, with PKCE S256.
- It keeps the token set in a Spring Session Data **Redis** session, behind an HttpOnly cookie.
- It relays the **caller's own** access token to the wrapper through Spring Cloud Gateway Server MVC (`TokenRelay`), refreshing it as needed.
- It has no service account and no client_credentials, and it decides nothing.

## Code map (`console-bff/`, package `rw.gov.risa.sso.bff`)

| Path | What |
|---|---|
| `config/OidcClientConfig` | Sets up the `sso-admin-ui` registration (endpoints below), the session-backed `HttpSessionOAuth2AuthorizedClientRepository`, and the `OAuth2AuthorizedClientManager` (authorization_code + refresh_token, skew 30 s). It fails startup in `staging`/`prod` when there is no secret. |
| `security/SecurityConfig` | Contains `oauth2Login` (PKCE forced with `OAuth2AuthorizationRequestCustomizers.withPkce()`), the authorization rules, the 401-JSON entry point, CSRF, logout and the security headers. |
| `security/SpaCsrfTokenRequestHandler` | Double submit: the raw `XSRF-TOKEN` cookie value comes back as the `X-XSRF-TOKEN` header or as the `_csrf` form field. |
| `security/LoginHandlers` | On success, redirects to `returnTo`, or else to the landing page (`/dashboard`, `/institution` or `/no-access`). On failure, redirects to `/?error=<oauth error code>` (e.g. `access_denied` or `mfa_required` from the wrapper). |
| `security/RpInitiatedLogoutSuccessHandler` | Builds `{public wrapper}/oauth2/logout?client_id&post_logout_redirect_uri&id_token_hint`. |
| `security/ReturnTo` | Same-origin path check for `returnTo`, so the login is not an open redirect. |
| `session/BffController` | `GET /bff/session` and `GET /bff/login`. |
| `session/SessionInfoService` | Decodes the access token without verifying it (the info is cosmetic) and falls back to the id_token. |
| `gateway/WrapperRouteConfig` | The `/api/**` → wrapper route: `sessionGuard` → `tokenRelay()` → `uri` + `stripPrefix(1)`. It removes `Cookie` and `X-XSRF-TOKEN` and propagates `X-Correlation-Id`. |
| `gateway/WrapperHttpClientConfig` | The relay client: JDK HttpClient pinned to **HTTP/1.1** (the default h2c upgrade drew `RST_STREAM`), redirects not followed, connect/read timeouts. |
| `spa/SpaConfig` | Static files from `CONSOLE_WEB_DIST` and/or `classpath:/static/`, with history fallback to `index.html`. There is no fallback for `/api`, `/bff`, `/oauth2`, `/login`, `/actuator` or for missing files that have an extension. `/assets/**` is cached immutable for a year; everything else is `no-cache`. |
| `config/FileSecretsEnvironmentPostProcessor`, `CorrelationIdFilter` | `*_FILE` secrets and `X-Correlation-Id` → MDC. JSON logs use the logstash encoder. |
| `application-memory-session.yml` | Profile `memory-session` for dev/tests without Redis. Single instance only. |
| `Dockerfile` (+ `Dockerfile.dockerignore`) | node:22 builds console-web, maven builds the jar, and the runtime is `eclipse-temurin:21-jre-alpine` with the SPA in `/app/static`. Non-root. Build it from the repo root. |

## OIDC endpoints (all at the wrapper)

| Endpoint | URL |
|---|---|
| authorize (browser) | `${SSO_PUBLIC_URL}/oauth2/authorize` |
| token (server) | `${SSO_BASE_URL}/oauth2/token` (`client_secret_post`, like the reference console) |
| JWKS (server, id_token signature) | `${SSO_BASE_URL}/oauth2/jwks` |
| end session (browser) | `${SSO_PUBLIC_URL}/oauth2/logout` |
| discovery (optional) | `BFF_OIDC_DISCOVERY=true` reads `${SSO_BASE_URL}/oauth2/.well-known/openid-configuration` at startup. Server-side URLs are rewritten from the public base to the internal one. The discovered `issuer` is deliberately ignored: the wrapper advertises Keycloak's *internal* URL, while tokens carry the public one. |

Pin `iss` with `BFF_OIDC_ISSUER`. Userinfo is not called unless `BFF_USERINFO_URI` is set.

**Register on the `sso-admin-ui` client:**

- redirect URI `https://<console>/login/oauth2/code/sso-admin-ui` (login starts at `/oauth2/authorization/sso-admin-ui`)
- post-logout URI `https://<console>/`

## Endpoints

| Method | Path | Behaviour |
|---|---|---|
| GET | `/bff/login?returnTo=/path` | Remembers a safe `returnTo` and 302s to `/oauth2/authorization/sso-admin-ui`, which 302s to the wrapper's authorize URL with `code_challenge_method=S256`, `state` and `nonce`. |
| GET | `/login/oauth2/code/sso-admin-ui` | Spring callback. Checks state, exchanges the code (secret + `code_verifier`), validates the id_token (JWKS, nonce, aud), changes the session id, then redirects to `returnTo` or the landing page. |
| GET | `/bff/session` | `{"authenticated","username","name","email","roles","platformRoles","platformAdmin","institutionAdmin","institutionSlug","institutions","landing","expiresAt"}`. camelCase matches `console-web/src/auth/session.ts`. Platform = `super_admin|admin|system_admin`. Institution admin = any `inst:<slug>:<role>`. This info is **cosmetic**. The call also refreshes a near-expiry token. Unauthenticated or dead session → **401 JSON**. |
| POST | `/bff/logout` | Needs CSRF. Invalidates the session (tokens removed from Redis) and clears the cookies. `Accept: application/json` gets `{"logout_url": ...}`. The console-web form POST (`_csrf`) gets a 302 to the wrapper's `/oauth2/logout` with `id_token_hint`. |
| ANY | `/api/**` | Needs a session, and CSRF for mutating methods. `/api` is stripped and the request relayed to `${SSO_BASE_URL}` with `Authorization: Bearer <caller's token>`. The wrapper's status and body pass through. |
| GET | `/actuator/health[/liveness,/readiness]` | Public. |
| GET | everything else | The SPA (history fallback). |

### Error responses

All errors use the envelope `{"error","message","details"}`.

| Status | `error` | When |
|---|---|---|
| 401 | `unauthenticated` | No session. `details.login_url = "/bff/login"`. |
| 401 | `session_expired` | The wrapper refused the refresh (`invalid_grant`, ...), or the token expired with no refresh token. The session is invalidated. |
| 502 | `token_refresh_failed` / `wrapper_unavailable` | Wrapper outage. The session is kept. |
| 403 | `csrf_or_access_denied` | Missing or invalid XSRF token. |

## Cookies and headers

**Session cookie:** `GORSSO_BFF_SESSION` (Spring Session, opaque id), `HttpOnly`, `Secure` (set `COOKIE_SECURE=false` only for plain-http dev), `SameSite=Lax`, `Path=/`, `Max-Age` 8h. The session timeout is 8h and the Redis namespace is `gorsso:bff:session`.

**`XSRF-TOKEN` cookie:** readable by JS, `SameSite=Lax`, and Secure set the same way as the session cookie.

**Headers:**

- CSP: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self' <wrapper public origin>`. Override it with `BFF_CSP`.
- `X-Frame-Options: DENY`
- `nosniff`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `COOP: same-origin`
- `Permissions-Policy`
- HSTS on HTTPS

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `3001` | |
| `SSO_BASE_URL` | `http://localhost:8000` | Wrapper base URL for server-side calls. |
| `SSO_PUBLIC_URL` | = `SSO_BASE_URL` | Browser-facing wrapper URL (authorize, logout). |
| `ADMIN_UI_CLIENT_ID` | `sso-admin-ui` | |
| `ADMIN_UI_CLIENT_SECRET` (`_FILE`) | — | Required in `staging`/`prod` (startup fails without it). |
| `ADMIN_UI_REDIRECT_URI` | `{baseUrl}/login/oauth2/code/{registrationId}` | |
| `ADMIN_UI_POST_LOGOUT_REDIRECT_URI` | `{request origin}/` | |
| `BFF_OIDC_ISSUER`, `BFF_JWK_SET_URI`, `BFF_USERINFO_URI`, `BFF_OIDC_DISCOVERY` | — / wrapper jwks / — / false | |
| `BFF_REFRESH_SKEW` | `30s` | Refresh ahead of expiry (the reference middleware's skew). |
| `REDIS_URL`, `REDIS_PASSWORD` (`_FILE`) | `redis://localhost:6379` | Session store. |
| `BFF_SESSION_TIMEOUT`, `BFF_COOKIE_MAX_AGE`, `BFF_SESSION_COOKIE`, `BFF_SESSION_NAMESPACE` | `8h`, `8h`, `GORSSO_BFF_SESSION`, `gorsso:bff:session` | |
| `COOKIE_SECURE` | `true` | |
| `CONSOLE_WEB_DIST` | — (classpath `static/`) | Built SPA directory. |
| `BFF_CSP` | built-in | |
| `BFF_WRAPPER_CONNECT_TIMEOUT`, `BFF_WRAPPER_READ_TIMEOUT` | `5s`, `120s` | |
| `BFF_FORWARD_HEADERS_STRATEGY` | `framework` | Honour `X-Forwarded-*` behind the TLS proxy. |
| `SPRING_PROFILES_ACTIVE=memory-session` | — | Dev without Redis. |

## Tests (`mvn -q -B -f console-bff/pom.xml verify`; `-Pit` adds Redis)

Without `-Pit`, 33 tests run. The WireMock-based tests stand in for the wrapper: it serves the token endpoint, JWKS and the `/api` upstream, and the tokens are signed with a test RSA key.

- **`LoginFlowTest`**: the real code flow through MockMvc:
  - the code_verifier the BFF sends hashes (S256) to the challenge it advertised
  - `client_secret` is posted
  - id_token checked against JWKS and nonce
  - landing pages; state mismatch; wrapper refusal mapped to `/?error=access_denied`
  - logout: CSRF required, JSON `logout_url` with `id_token_hint`, cookie cleared, form POST with `_csrf` gets a 302
- **`TokenRelayTest`**:
  - the caller's bearer is forwarded (not the browser's `Authorization`), with no `Cookie`, `/api` stripped, query kept, correlation id propagated
  - an expired token is refreshed once and the rotated refresh token stored
  - a token within the skew is refreshed ahead
  - `invalid_grant` → 401 and the session invalidated
  - a 503 from the wrapper → 502 and the session kept
  - an expired token with no refresh token → 401
  - upstream errors pass through
  - no authorized client → 401
- **`BffSecurityTest`**:
  - 401 JSON for `/bff/session` and `/api/**`
  - the `/bff/session` flags
  - the PKCE authorize redirect, and that `returnTo` cannot be an open redirect
  - CSRF: 403 and nothing relayed vs. double submit relayed, with `X-XSRF-TOKEN` and `Cookie` stripped
  - security headers
  - SPA fallback and the reserved-prefix and missing-asset exclusions
  - health is public
- **`UnitTests`**: `ReturnTo` and role parsing.
- **`StartupGuardTest`**: `prod` without a secret fails startup.
- **`RedisSessionIT`** (`@Tag("it")`, Testcontainers `redis:alpine`):
  - login stores the session in Redis (`gorsso:bff:session:sessions:*`)
  - the cookie is opaque, HttpOnly and Secure
  - a **second BFF instance** on the same Redis serves `/bff/session` and relays `/api` with only the cookie
  - logout on instance 1 ends the session on instance 2

## Gaps and notes

- The `sso-admin-ui` client's redirect URIs must include `/login/oauth2/code/sso-admin-ui`. The reference used `/api/auth/callback`, which would collide with the `/api` relay. The wrapper or realm seed owner has to register it.
- An id_token returned by a refresh is not swapped into the security context, so logout uses the login-time id_token as `id_token_hint`, which Keycloak accepts even when expired.
- There is no rate limiting in the BFF; that is the wrapper's job, per route.
