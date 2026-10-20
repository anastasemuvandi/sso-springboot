# Auth gateway (`/auth`, `/oauth2`, `/saml`, `/account` pages)

Spec §5.1, §5.12 (public pages), invariants §7.3–7.5 and §7.8. Port of the reference
`api/{auth,oauth2,saml,account_setup}.py`, `services/auth_service.py`, `core/{pkce,jwt_verify}.py`.

## Code

| Path (under `sso-wrapper/src/main/java/rw/gov/risa/sso/wrapper/`) | What |
|---|---|
| `service/gateway/TokenGates.java` | **The three gates, in one place.** `pass(Door, accessToken, clientId, ip)`: verify the token (core validator) → `preferred_username` required (401) → `azp` must equal the client (400 `invalid_grant`) → `ClientAccessService.enforceLogin` (403 `access_denied`, audited `LOGIN_DENIED`) → `JoinKeyService.enforceLogin` (403 `join_key_*`, fails open on infra error) → `MfaService.enforcePasswordGrant` (403 `mfa_required`, audited `auth.mfa.password_grant_refused`) when the door is a password door. `Door` = `LOGIN`, `AUTHORIZE_INTERNAL`, `TOKEN_WRAPPER_CODE`, `TOKEN_BROWSER_CODE`, `TOKEN_REFRESH`. |
| `service/gateway/TokenVerifier.java` | Wraps the core `JwtDecoder` (RS256, JWKS, pinned issuer, 5 s skew, known `azp`). |
| `service/gateway/AuthGatewayService.java` | Login, logout, fail-closed verify, userinfo. |
| `service/gateway/OAuth2GatewayService.java` | Authorize, authorize-internal, token (3 grants), discovery, logout, JWKS, introspection. |
| `service/gateway/ClientAuthenticator.java` | Client authentication (form `client_secret` or HTTP Basic, never both). A confidential client must present its secret; the stored secret is only read to compare it (constant time) with the one presented. |
| `service/gateway/AuthCodeStore.java`, `RedisAuthCodeStore.java` | Wrapper-issued codes: `gorsso:oauth2:code:<sha256(code)>`, TTL `AUTH_CODE_TTL` (300 s), redeemed with **GETDEL** (spent on the first attempt, whatever the outcome). Redis down → 503. |
| `service/gateway/Pkce.java` | S256 only (RFC 7636); `plain` never verifies. |
| `service/gateway/RedirectUris.java`, `RegisteredClient.java` | Exact redirect match; post-logout list (`##`, `+` = redirect URIs). |
| `service/gateway/KeycloakOidc.java` | Keycloak's OIDC/SAML endpoints over the internal URL (`/token`, `/token/introspect`, `/logout`, `/certs`, SAML descriptor). |
| `service/gateway/GatewayDirectory.java` | Admin API as the service account (client lookup, secret compare, session lookup/end, groups, clients). Any failure → 503 (fail closed). |
| `service/gateway/SamlGatewayService.java`, `AccountPageLinks.java`, `GatewayProperties.java`, `GatewayErrors.java` | SAML flag, the relative-only `return_to` rule, `sso.gateway.*`, codes. |
| `api/auth/AuthController.java`, `api/oauth2/OAuth2Controller.java`, `api/saml/SamlController.java`, `api/account/AccountSetupController.java`, `api/account/AccountTexts.java` | Thin controllers. |
| `resources/templates/account/*.html`, `resources/static/account/account.css`, `resources/i18n/account{,_rw}.properties` | Account pages (Thymeleaf, Government branding, en + rw). |

Config (`application.yml`, block `auth-gateway`): `ENABLE_SAML` (false), `AUTH_CODE_TTL` (300s),
`GATEWAY_HTTP_TIMEOUT` (10s), `REDIRECT_URI_ALLOW_WILDCARDS` (false: exact match only; true honours a
registered trailing `*` as Keycloak does, at a path boundary).

## Endpoints

| Route | Notes | Rate limit (per IP) |
|---|---|---|
| `POST /auth/login` | JSON `{username, password, client_id, client_secret?}` (or HTTP Basic). Password grant, then all three gates on the minted token; a refusal ends that Keycloak session. Answer `{access_token, refresh_token, id_token, token_type:"bearer", redirect_url}`. Bad password → 401 `invalid_credentials`. | 10/min |
| `POST /auth/logout` | Bearer. Ends the token's SSO session (`sid`) through the Admin API. `{"message":"Successfully logged out"}`. | default |
| `GET /auth/verify` | Bearer (resource server: invalid → 401). `{"valid": bool}`; the session must still be live in Keycloak. Any error reading it gives `false`. | default |
| `GET /auth/userinfo` | Bearer. Reference `UserInfo` shape (`agencies`, `clients`, `current_client`, …). | default |
| `GET /oauth2/authorize` | Validates `response_type=code`, PKCE S256 (43-char challenge), the client and an **exact** redirect URI, then 302 to Keycloak's **public** auth endpoint, forwarding `state`, `nonce`, `acr_values`, `scope`, `prompt` and `login_hint`. Every refusal is a 400 with no redirect. | 60/min |
| `POST /oauth2/authorize-internal` | Bearer = `/auth/login` token. JSON `{client_id, redirect_uri, refresh_token, id_token, code_challenge, code_challenge_method}`. Checks S256, the registered redirect, `azp`, then the gates. Answer `{"authorization_code": …}`. | 60/min |
| `POST /oauth2/token` | Form. `authorization_code`: a wrapper code (consumed first, then client, redirect and PKCE checked, then the gates) or else Keycloak's code (exchanged, then gated without the password-MFA gate). `refresh_token`: Keycloak refresh, then the gates (a revoked person is refused and their session ended). `client_credentials`: confidential clients only, no user gates. Errors use RFC 6749 `{error, error_description}` plus the envelope's `message`/`details`, `no-store`, and `WWW-Authenticate: Basic` on 401 `invalid_client`. | 120/min |
| `GET /oauth2/.well-known/openid-configuration` | Wrapper authorize/token/userinfo/jwks/logout/introspect URLs. `issuer` = Keycloak issuer (`KEYCLOAK_LOCAL_ISSUER/realms/<realm>`). S256 only. | default |
| `GET /oauth2/logout` | A `post_logout_redirect_uri` needs `client_id` and must be registered (audited `LOGOUT_REDIRECT_DENIED`). Then 302 to Keycloak's public end-session URL. With no parameters the answer is `{"message":"Logged out successfully"}`. | 60/min |
| `GET /oauth2/jwks` | Realm JWKS, proxied. 502 on upstream failure. | default |
| `POST /oauth2/introspect` | Form `token`. Only an authenticated confidential client may call it (form or Basic). Keycloak RFC 7662 answer. | default |
| `GET /saml/metadata` | 404 unless `ENABLE_SAML`; otherwise Keycloak's descriptor (`application/samlmetadata+xml`, `no-store`). | default |
| `GET /account/setup?token=` | Only checks the link (mail scanners cannot spend it). Shows the form or "link can no longer be used". | 60/min |
| `POST /account/setup` | `SetupLinkService.complete`, then lists the bound apps the person can open now, each linking to the client's home URL. A refused password re-shows the form with Keycloak's message. | 20/hour |
| `GET /account/setup-link` | Resend form (prefilled `username`, `lang`, `return_to`). | 60/min |
| `POST /account/setup-link` | `SetupLinkService.requestResend`. The page is identical for every outcome; the real outcome is audited by the service, which also throttles per account. | 10/hour |

The account pages are `@Hidden` from OpenAPI, as in the reference. Each page sets its own CSP
(`style-src 'self'; form-action 'self'`, no script), `no-referrer` and `no-store`.

`return_to` is accepted only as a relative path. It is resolved against `KEYCLOAK_PUBLIC_URL`.
Absolute, `//host`, backslash and control-character values are dropped, so no back link is shown for them.

Audit events: `LOGIN_SUCCESS`/`LOGIN_FAILURE`, `LOGIN_DENIED` (written by the gate services),
`LOGOUT` (success and failure), `AUTHORIZE_START` (success and failure), `AUTHORIZE_REDIRECT_DENIED`,
`AUTHORIZE_INTERNAL_CODE` (success and failure), `TOKEN_ISSUED`, `TOKEN_REFRESHED`, `TOKEN_REJECTED`,
`LOGOUT_REDIRECT_DENIED`.

## Deliberate differences from the reference (spec wins)

- **Secrets.** A confidential client must present its own secret on `/oauth2/token`, `/oauth2/introspect` and `/auth/login`. The reference looked the secret up for the caller.
- **`/auth/logout`.** Ends the session through the Admin API using `sid`. The reference passed the access token to Keycloak's logout as if it were a refresh token.
- **No skipped gates.** A token without `preferred_username` is 401 at every door; the reference skipped the gates for such tokens. The gates also run on a wrapper code at `/token`, and the MFA gate also runs on refresh.
- **`/auth/verify`.** Checks that the session is live through the Admin API rather than introspection with a looked-up secret.
- **Redirect URIs.** Must match exactly; the reference allowed a trailing `*` (see `REDIRECT_URI_ALLOW_WILDCARDS`). The redirect is checked at `authorize-internal` too, and the presented token's `azp` must be the requesting client.
- **Gate refusals.** When a gate refuses a token Keycloak already minted, that session is ended at once.
- **Discovery.** `jwks_uri` is the wrapper's `/oauth2/jwks`, and `introspection_endpoint` is advertised.

## Tests

| Test | Covers |
|---|---|
| `service/gateway/EveryDoorRunsEveryGateTest` (30) | Parameterised over doors × gates. A refusing gate gives its 403 code at every door. With all gates open, every door consults every gate for its grant type. The exception is MFA on a browser code, which is explicitly asserted to be not consulted. Every door also answers 401 for a token without `preferred_username` and for a forged token. |
| `service/gateway/TokenGatesTest` (9) | The real `MfaService`/`mfa-policy` acr rule: acr 1 is refused (audited), acr 2 passes, `mfa-exempt` passes, refresh is gated, an unreadable switch fails open. Also gate order, short-circuiting, and the `azp` mismatch. |
| `api/oauth2/OAuth2ControllerTest` (40) | WireMock Keycloak, RS256 test key, real JWT validator. Covers S256-only (plain refused), malformed or missing challenge, exact redirect match (5 near-misses), unknown client, forwarded parameters, `authorize-internal`, and confidential clients (refused without a secret and never forwarded; Basic or form accepted; wrong secret or both methods refused). Single use: a second redemption fails, including when the first failed on PKCE or a gate. Also redirect binding, Keycloak code exchange and gating, a gate refusal ending the session, refresh through all gates, refresh refused for a revoked person, RFC 6749 pass-through, `client_credentials`, discovery, JWKS, logout redirect validation, introspection auth, and Keycloak unreachable → 503. |
| `api/auth/AuthControllerTest` (16) | Login shape and gates; confidential client without a secret; Basic; public client; bad password → 401 and audited; refusal ends the session; `/auth/verify` fails closed (no bearer, forged, expired, garbage, no `preferred_username`, `azp` unverifiable → 401; session dead or unreadable → `false`); logout; userinfo shape. |
| `service/gateway/GatewayAuditTrailTest` (5) | The **real `HttpAuditEmitter`** posting to a WireMock audit service, with the real `ClientAccessService.enforceLogin` (spy). Login success, login failure, token issued, refresh, and denials at login and refresh all reach `/audit/events`. |
| `api/account/AccountSetupControllerTest` (8) | GET only checks; POST lists apps with URLs (HTML-escaped); the refused-password, differ and dead-link paths; identical resend answer for 5 outcomes; `rw`; relative `return_to` is linked, and absolute or off-site values are never linked; CSP. |
| `api/saml/SamlControllerTest` (2), `service/gateway/GatewayRulesTest` (12) | SAML flag and proxy; PKCE RFC vector; redirect rules; Basic decoding and conflicts; back-link rule. |
| `service/gateway/AuthGatewayKeycloakIT` (5, `@Tag("it")`) | Real Keycloak 26.2 (`support/KeycloakIT`) and Redis (`support/RedisIT`). An unassigned person is refused at `/auth/login` (403 `access_denied`). A confidential client without a secret gets 401. Full flow: a real password login, then `/auth/verify`, then `authorize-internal`, then the token exchange (PKCE, Basic); a second redemption gets 400; refresh succeeds; after access is revoked, refresh gets 403. Logout makes `/auth/verify` answer `false`. The Redis code store is atomic, keyed by hash, and has a TTL. |

## Gaps

- `/auth/verify` treats a token with no session (`sid`) as valid once its signature checks out. That is a lightweight or service token, never a browser or password login.
- The password-grant MFA gate runs on refresh. It relies on Keycloak carrying the login's `acr` into refreshed tokens. If an admin turns TOTP on for a client, people logged in at acr 1 are refused at their next refresh and must sign in again through the browser.
- The Kinyarwanda strings reuse the reference translations and still need review by a native speaker.
