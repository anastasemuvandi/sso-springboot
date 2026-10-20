# Wrapper core (`sso-wrapper`, package `rw.gov.risa.sso.wrapper`)

Everything a feature builds on. Read this before adding code to the wrapper.

## Packages and layering (ArchUnit-enforced)

| Package | Holds | May depend on |
|---|---|---|
| `api.<feature>` | `@RestController`s — thin: bind, validate, call one service, map to DTO | `service`, `security`, `error`, `ratelimit`, DTOs |
| `service.<feature>` | business logic and **every** Keycloak / Redis / DB / HTTP call | `policy`, `keycloak`, `repository`, `audit`, `config`, `error`, `security` |
| `keycloak` | `KeycloakAdminFactory`, low-level Admin API helpers | — |
| `repository` / `identity` | JPA entities + Spring Data repositories (identity mirror) | — |
| `policy.<module>` | pure Java rules (no Spring/HTTP/Keycloak) — see `policy-modules.md` | nothing outside `policy` |
| `config`, `security`, `error`, `audit`, `ratelimit`, `web`, `health` | core (this doc) | — |

Controllers never call `KeycloakAdminFactory` directly.

## Configuration

- `config/SsoProperties` (`sso.*`): `debug` (DEBUG), `environment` (APP_ENV dev|staging|prod),
  `apiBaseUrl`, `keycloak.{serverUrl, publicUrl, localIssuer, realm, clientId, clientSecret, exemptClients}`
  (+ helpers `issuer()`, `internalRealmUrl()`, `publicRealmUrl()`), `cors.allowedOrigins`.
- Each feature defines its OWN `@ConfigurationProperties(prefix = "sso.<feature>")` record next to
  its service and maps the reference env var names in `application.yml` under the
  "FEATURE BLOCKS" marker (add with Edit; never rewrite the file).
- `<NAME>_FILE` secrets: `config/FileBackedSecretsPostProcessor.FILE_BACKED` (add names there).
- `config/RequiredSecretsValidator`: staging/prod refuse to start without required secrets.
- The identity DB autoconfigs (DataSource/JPA/Flyway) are **excluded**; the mirror feature wires
  its own beans only when `IDENTITY_DATABASE_URL` is set.

## Security

- `security/SecurityConfig` + `security/RouteRules` decide the gate per path:
  `ADMIN_TIER` (realm role super_admin|admin|system_admin), `SELF_SCOPED` (`/institution-admin/**`,
  `/employees/**` — authenticated, feature resolves scope), `AUTHENTICATED` (`/auth/verify`,
  `/auth/userinfo`), `PUBLIC` (protocol surface, account setup, `/ippis/events`, health, docs).
  If you add a new top-level prefix, add it to `RouteRules` deliberately.
- JWT: RS256 only, JWKS via internal URL, issuer `KEYCLOAK_LOCAL_ISSUER/realms/<realm>`, 5 s skew,
  `azp` must be a realm client (`keycloak/KnownClientsCache`, 60 s). No `preferred_username` → 401.
  `JwtDecoderConfig.validator(issuer, cache)` is reusable (the gateway verifies tokens it mints with it).
- Caller: `@AuthenticationPrincipal CallerPrincipal caller` — `userId, username, email, name, azp,
  realmRoles, clientRoles (incl. realm-management), institutionClaim, sessionId, accessToken, claims`;
  helpers `isPlatformAdmin()`, `isSuperAdmin()`, `hasRealmRole()`. `security/Callers.current()` for
  non-controller code. **Audit/lifecycle actors come from here, never from a request body.**
- Method security is enabled (`@PreAuthorize`).

## Keycloak

`keycloak/KeycloakAdminFactory`:
- `asServiceAccount(realm -> ...)` — fresh `sso-client` client-credentials client per unit of work,
  closed afterwards. Group several calls in one lambda. Never cache a client.
- `asCaller(caller.accessToken(), realm -> ...)` — the caller's own token (delegated calls,
  `DELEGATED_EXECUTE_AS_CALLER`). Never falls back to the service account.
- `asServiceAccountInRealm(realm, ...)`, `newServiceAccountClient()`, `newCallerClient(token)`.
- Admin-client failures are `jakarta.ws.rs.WebApplicationException`; the global handler maps them
  (404→not_found, 409→conflict, 403→forbidden, 400→invalid_request, other→502 keycloak_error,
  unreachable→503). Catch them in services when you need a different answer.
  `error/KeycloakErrors.status(e)` / `.message(e, fallback)`.

## Errors

Throw `error/ApiException` (`status, error, message, details`); factories `badRequest, unauthorized,
forbidden, notFound, conflict, unavailable, badGateway`. Codes in `error/ErrorCodes` (add yours there).
Body: `{"error": "...", "message": "...", "details": {...}}`. Bean Validation failures → 422
`validation_error` with `details.fields`.

## Audit

`audit/AuditEmitter.emit(AuditEvent.of(AuditEventType.X).actor(caller.username()).clientId(..)
.requestIp(RequestInfo.clientIp()).institution(slug).targetUser(..).put(k, v))`.
Writes a structured `audit` log line immediately, POSTs `{event_type, user_id, app_id, performed_by,
ip_address, institution, target, details, severity}` to `AUDIT_SERVICE_URL/audit/events`
(header `X-Audit-API-Key`) off-thread; failures are parked in Redis list `audit:outbox` and re-sent
every 15 s (ShedLock). Never throws. Add event types to `AuditEventType`.
In tests mock it with `@MockitoBean(answers = Answers.CALLS_REAL_METHODS) AuditEmitter audit` (so the
`emit(Builder)` default delegates to `emit(AuditEvent)`) and `verify(audit).emit(argThat((AuditEvent e) -> ...))`.

## Rate limiting

`@RateLimit(value = "30/minute", key = RateLimit.Key.CALLER)` on a controller method (Bucket4j on
Redis, shared across instances; fails open if Redis is down). Global default `RATE_LIMIT_DEFAULT`
(1000/hour per IP). `RATE_LIMIT_ENABLED=false` turns it all off (tests do).

## Other

- `web/CorrelationIdFilter` (`X-Correlation-Id`, MDC `correlationId`), `web/NoStoreFilter`,
  `web/RequestInfo.clientIp()` (X-Forwarded-For only via `server.forward-headers-strategy`).
- JSON logs (logstash encoder); profile `plainlog` for human-readable logs.
- ShedLock: `@Scheduled` + `@SchedulerLock(name=..., lockAtMostFor=...)` (Redis provider, lease).
- Executors: `auditExecutor`.
- `health/HealthController`: `GET /health/`, `GET /health/ready` (Keycloak realm + Redis).
- springdoc: `/v3/api-docs`, Swagger UI at `/docs/api`.

## Tests

- `support/WrapperTest` (meta-annotation: full context, MockMvc, profiles test+plainlog, no live
  infra needed) and `support/TestCallers` (`superAdmin()`, `admin()`, `systemAdmin()`,
  `institutionAdmin(slug, tierRole)`, `plainUser()`, `as(CallerPrincipal)`, `caller(...)`).
- `contract/OpenApiContractTest`: committed `sso-wrapper/openapi.json` must match. After adding or
  changing endpoints run
  `mvn -q -B -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dopenapi.update=true`.
- `security/SecurityGateTest`, `audit/HttpAuditEmitterTest`, `security/CallerTokenConverterTest`.
- Testcontainers suites: `@Tag("it")`, run with `mvn -Pit`.

## Ops commands (CLI)

`java -jar sso-wrapper.jar <command> [args]` runs without a web server and exits with the
command's code (`help` lists them). Add a command by implementing `cli/OpsCommand` as a bean
(`name()`, `description()`, `int run(List<String> args)`). Scheduled jobs: guard with
`@ConditionalOnProperty(name = "sso.ops-mode", havingValue = "false", matchIfMissing = true)` if a
job must not start in ops mode (`spring.task.scheduling.enabled=false` is also set there).
Expected commands: `delegated-validate`, `delegated-apply`, `delegated-check`, `fgap-plan`,
`fgap-plan-check`, `registry-validate`, `mfa-install`, `mfa-enrol`, `verify`, `ippis-reconcile`,
`ippis-leavers`, `seed-identity-mirror`.

**application.yml feature blocks** are separate YAML documents: start yours with `---`, then a
top-level `sso:` holding only your `sso.<feature>` keys. Spring Boot merges documents; a second
top-level `sso:` key inside one document is rejected at startup.
