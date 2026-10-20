# Integration kit (spec §5.20): starter, SDKs, sample apps

Everything a ministry needs to plug an app into GoRSSO. Integrator-facing guide:
`docs/integration/INTEGRATION_GUIDE.md`.

## `sso-spring-boot-starter/` (`rw.gov.risa.sso.starter`)

Module of the root POM; `mvn -q -B -N install && mvn -q -B -f sso-spring-boot-starter/pom.xml verify`.
Auto-configurations (`META-INF/spring/...AutoConfiguration.imports`):

| Class | Does | Backs off / opt-out |
|---|---|---|
| `GorssoResourceServerAutoConfiguration` | `JwtDecoder` (Nimbus, **RS256 only**, JWKS `gorsso.jwk-set-uri` or `<issuer>/protocol/openid-connect/certs`; validators: alg=RS256, exp/nbf with `gorsso.clock-skew` (5 s), `iss` = `gorsso.issuer-uri`, `azp` ∈ `gorsso.client-id` + `gorsso.allowed-azp`); authority mappers; `GorssoAuthoritiesConverter`; `GorssoJwtAuthenticationConverter` (name = `preferred_username`, else `sub`); `InstitutionRolesResolver`; `JoinKeyResolver`; SpEL bean `gorsso` | every bean `@ConditionalOnMissingBean`; decoder only when `gorsso.issuer-uri` is set; `gorsso.enabled`, `gorsso.resource-server.enabled`, `gorsso.roles.{institution,client,scopes}.enabled`, `gorsso.join-key.enabled` |
| `GorssoWebSecurityAutoConfiguration` | default stateless chain: `gorsso.resource-server.permit-all` open (health/info/error by default), everything else needs a GoRSSO bearer | any app `SecurityFilterChain`; `gorsso.resource-server.default-chain=false` |
| `GorssoWebMvcAutoConfiguration` | `@JoinKey String|JoinKeyValue` and `InstitutionRoles` controller arguments; `GorssoErrorAdvice` (403 `{"error":"join_key_missing",...}`) | servlet apps only |

Authorities: realm `inst:<slug>:<role>` → `INST_<slug>_<role>` (legacy UPPERCASE slugs lower-cased;
other realm roles ignored); `resource_access.<clientId>.roles` → `ROLE_<role>` (clients:
`gorsso.roles.client.client-ids`, default the client id); `scope` → `SCOPE_*`. Add a
`GorssoAuthorityMapper` bean to contribute more.

`InstitutionRoles`: `slug()` = `gorsso.institution.slug` → `institution` claim → the single
institution with roles; `hasRole("viewer" | "inst:minagri:viewer" | "inst:minagri:*")`,
`roles()`, `isMember()`, `institutions()`. SpEL: `@gorsso.memberOfInstitution()`,
`@gorsso.institutionRole('viewer')`, `@gorsso.memberOf('minagri')`, `@gorsso.hasJoinKey()`.

`JoinKeyResolver`: reads claim `join_key` (`gorsso.join-key.claim`); `gorsso.join-key.column` =
`nid|phone|email` (unset = email, spec §5.6) — e-mail values are lower-cased, others trimmed.
`JoinKeyValue.toString()` never prints the value.

Tests (40): `GorssoAutoConfigurationTest` (Web/ApplicationContextRunner: wiring, opt-outs,
back-off, fail-fast without client id, non-web), `GorssoJwtDecoderTest` (real RS256 tokens
against a local JWKS: accepts; refuses HS256, foreign/missing azp, wrong iss, expired),
`GorssoMockMvcTest` (`jwt()` + a real bearer through the decoder: 401/403, `@JoinKey`,
`InstitutionRoles`, `@PreAuthorize` with `INST_*`/`ROLE_*`/`@gorsso`), `RoleMappingTest`,
`JoinKeyResolverTest`.

Method security is NOT enabled by the starter (a second `@EnableMethodSecurity` would clash);
apps add `@EnableMethodSecurity`. Reactive (WebFlux) apps are not covered.

## `sdk/`

- `sdk/generate.sh` + `sdk/config/{java,typescript,python}.yaml`: openapi-generator
  `v7.14.0` in Docker → `sdk/java` (Java 21 `native`), `sdk/typescript` (`typescript-fetch`).
  Output gitignored; `VERIFY=1` compiles it. Proven against the reference contract.
- `sdk/react/` — `@gorsso/react` (see `sdk/README.md`): `GorssoProvider` (`pkce` | `bff`),
  `useAuth`, `useSession`, `useJoinKey`, `useAuthFetch`, `<RequireAuth>`, `<RequireRole>`.
  `npm test` (40 Vitest tests: RFC 7636 App. B vector, verifier rules, authorize/logout URLs,
  callback state single-use + CSRF, nonce/aud/exp/iss, refresh rotation, gate errors, memory-only
  tokens, BFF session/login/logout + XSRF header, guards incl. React Router `<Outlet/>`),
  `npx tsc --noEmit`, `npm run build`.

## `sample-apps/`

- `sample-apps/api` — standalone Spring Boot app (parent `spring-boot-starter-parent`, NOT in the
  root modules) using the starter. Profiles `minagri` (client `minagri-kubaka-web`, port 8081,
  join column nid) / `minisante` (`minisante-hmis`, 8082, email). `GET /api/public/info` (open),
  `GET /api/me` (username, join_key + column, institution, institution roles, client roles),
  `GET /api/records` (`@PreAuthorize("@gorsso.memberOfInstitution()")` = any `inst:<slug>:*`).
  Build: install the starter, then `mvn -q -B -f sample-apps/api/pom.xml verify` (5 tests).
  Docker: `docker build -f sample-apps/api/Dockerfile -t gorsso/sample-api .` (repo-root context;
  `PORT=8080` in the container).
- `sample-apps/web` — Vite + React SPA using `@gorsso/react` via `file:../../sdk/react` (build the
  SDK first), PKCE mode. `.env.minagri` / `.env.minisante` (client ids from
  `configs/institutions.yaml`; redirect `http://localhost:5173|5174/callback`). Pages: `/`,
  `/callback`, `/me` (`RequireAuth`), `/records` (`RequireRole inst:<slug>:*`). `npm run build`
  builds both into `dist/<mode>`; `npm test`. Docker:
  `docker build -f sample-apps/web/Dockerfile --build-arg MODE=minagri -t gorsso/sample-web:minagri .`
  (nginx, `/api` → `$API_UPSTREAM`).

Both images were built and smoke-tested together (`/api/public/info` through nginx, SPA routes,
401 on `/api/records` without a token).

## Gaps / not done

- **The sample SPA needs public clients.** `minagri-kubaka-web` and `minisante-hmis` are
  confidential by default in `configs/institutions.yaml` (`public` unset). For the PKCE sample to
  exchange codes without a secret, set `public: true` on those apps (or add `<slug>-<app>-spa`
  public clients), and add `http://localhost:5173`/`5174` to the wrapper's CORS allow-list
  (`/oauth2/token` is called from the browser). Not changed here (outside this task's paths).
- No end-to-end login test against a live Keycloak + wrapper: the wrapper's `/oauth2/*` gateway
  was not implemented in this repo yet when this was written.
- No Python sample app port (the reference minisante sample was FastAPI); both ministries use the
  one parameterised Spring Boot API + React SPA, as specified.
- Generated SDK publishing (Maven/npm registry) is left to CI.
