# MFA (TOTP): shared policy, Keycloak provider, image, flow definition

SPEC §5.7 (and §3, §5.18, §8). Ported from the reference `keycloak/extensions/gorsso-mfa`,
`src/app/core/mfa_policy.py`, `infra/terraform/mfa.tf` and `scripts/kc26_enable_mfa.py`.

| What | Where |
|---|---|
| Shared TOTP rule (pure Java, zero runtime deps) | `mfa-policy/src/main/java/rw/gov/risa/sso/mfa/policy/` |
| Declarative `browser-totp` flow | `mfa-policy/src/main/resources/gorsso/mfa-flow.json` (classpath `/gorsso/mfa-flow.json`) |
| Keycloak SPI (`gorsso-mfa.jar`, shades mfa-policy) | `keycloak-extensions/` (package `rw.gov.risa.sso.mfa`) |
| Keycloak 26.2 image | `keycloak/Dockerfile` (+ `keycloak/Dockerfile.dockerignore`), context = repo root |
| Terraform flow (source of truth for the shape) | `infra/terraform/mfa.tf`, `base.tf` (`acr.loa.map`) |

## 1. The rule (`mfa-policy`)

- Realm attribute `mfa_totp_mode` = `off` | `per_client` | `all` (trimmed, case-insensitive).
- Unset or blank: fall back to the legacy `mfa_totp_enforced`. Unset or blank means `all`. `false` (trimmed,
  any case) means `off`. Any other value means `all`.
- An unknown mode means `all` (fail closed).
- `per_client`: client attribute `sso.client.mfa_required`. Unset or blank means required. Only `false`
  (trimmed, any case) opts out.
- Exemption is the **realm role `mfa-exempt`**. Keycloak checks it with "Condition - user role" (negated)
  in the flow. The step-up authenticator checks it for the SSO-cookie user. The wrapper reads it from
  `realm_access.roles` in the token. The reason, granter and grant time are kept in the user attributes
  `mfa_exempt_reason`, `mfa_exempt_granted_by` and `mfa_exempt_granted_at`. A reason must be 15..500 characters after whitespace
  is collapsed.
- `acr.loa.map` = `{"1":1,"2":2}`: acr `1` means password only, acr `2` means TOTP or a recovery code was presented.
- One deliberate decision: the reference Python `is_enforced("yes")` returned `false`, but Keycloak's
  `MfaRequirement` read it as `all`. The port uses Keycloak's reading everywhere. `attributeMatches`
  keeps the generic "expected value" comparison, so `isOn("yes","true",true)` is still `false`.

Public API (all static unless noted):

```java
final class MfaConstants   // MODE_ATTRIBUTE, LEGACY_ATTRIBUTE, CLIENT_ATTRIBUTE, FIRST_ENABLED_ATTRIBUTE,
                           // ACR_LOA_MAP_ATTRIBUTE/VALUE, EXEMPT_ROLE(+_DESCRIPTION), EXEMPT_*_ATTRIBUTE(S),
                           // MIN/MAX_REASON_LENGTH, CONFIGURE_TOTP, CONFIGURE_RECOVERY_AUTHN_CODES, REQUIRED_ACTIONS,
                           // OTP_CREDENTIAL_TYPE, RECOVERY_CODES_CREDENTIAL_TYPE, LOA_PASSWORD=1, LOA_TOTP=2,
                           // ACR_PASSWORD="1", ACR_TOTP="2", LOA_MAX_AGE_SECONDS=36000, MFA_FLOW_ALIAS,
                           // DEFAULT_BROWSER_FLOW, FLOW_DEFINITION_RESOURCE, PROVIDER_* ids,
                           // REQUIRED_AUTHENTICATORS, RECOVERY_CODE_FORM, EVENT_TYPES
enum MfaMode { OFF, PER_CLIENT, ALL; String value(); static Optional<MfaMode> fromValue(String) }
enum BulkSkipReason { DISABLED, SERVICE_ACCOUNT, EXEMPT, ALREADY_ENROLLED, ALREADY_PENDING; String code() }
final class MfaPolicy {
  MfaMode effectiveMode(String modeRaw, String legacyRaw);
  MfaMode effectiveMode(Map<String,?> realmAttributes);
  String  mode(String modeRaw, String legacyRaw);                 // = MfaRequirement.mode
  boolean legacyEnforced(String raw);
  boolean attributeMatches(String value, String expected, boolean whenUnset);   // = isOn
  MfaMode requestedMode(String value);                            // IllegalArgumentException -> 400
  String  encode(boolean);
  boolean clientRequires(String clientValue);
  boolean applies(MfaMode mode, String clientValue);
  boolean applies(String modeRaw, String legacyRaw, String clientValue);        // = MfaRequirement.applies
  boolean applies(Optional<String>, Optional<String>, Optional<String>);
  Optional<String> clientValue(Map<String,?> clientRepresentation);
  Optional<String> delegatedChangeRefusal(boolean required);      // on: allowed, off: refused
  String  validateReason(String reason);                          // IllegalArgumentException
  boolean isExempt(Collection<String> realmRoles);
  boolean isExemptClaims(Map<String,?> claims);
  boolean namesExemptRole(Collection<String> roleNames);          // generic role paths must refuse
  boolean presentedTotp(Object acr);   boolean presentedTotpClaims(Map<String,?> claims);
  Optional<String> passwordGrantRefusal(Map<String,?> claims, boolean enforced);
  Optional<BulkSkipReason> bulkEnrolmentSkip(boolean enabled, String username, boolean serviceAccountLinked,
                                             Collection<String> requiredActions, boolean hasOtp, boolean exempt);
  Optional<BulkSkipReason> bulkEnrolmentSkip(Map<String,?> userRep, boolean hasOtp, boolean exempt);
  Optional<String> attribute(Map<?,?> attributes, String name);   // String or List<String> values
  Optional<Integer> parseLevel(String raw);
}
record SwitchState(String legacyRaw, String browserFlow, String modeRaw) {
  static SwitchState fromRealm(Map<String,?> realmRep);
  MfaMode mode(); boolean attributeOn(); boolean flowReady(); boolean effective();
  boolean appliesTo(String clientValue);
  Map<String,Object> describe();   // mode, raw_mode, enforced, effective, raw, browser_flow, flow_ready
}
final class MfaFlowDefinition { static InputStream open(); static String json(); }
```

## 2. Keycloak providers (`gorsso-mfa.jar`)

| Provider id | Class | Role |
|---|---|---|
| `gorsso-mfa-step-up` | `StepUpAuthenticator` | First step. When TOTP applies to the client and the cookie user is not exempt, it raises the requested LoA client note to 2 (it never lowers it). |
| `gorsso-condition-realm-attribute` | `RealmAttributeCondition` (+ flow callback) | "TOTP applies to this client" via `MfaPolicy.applies`. `loa` config sets the level on sub-flow success, and the LoA map is copied to the user session in `onTopFlowSuccess`. |
| `gorsso-condition-credential` | `CredentialCondition` | Checks whether the user has (or has not) a stored credential of `credential_type` (`present`). |
| `gorsso-recovery-reenrol` | `RecoveryReenrolAuthenticator` (+ callback) | After a recovery code: adds CONFIGURE_TOTP on the user, posts `auth.mfa.recovery_code_used`, and **removes the OTP credentials in `onTopFlowSuccess`** (`remove_existing`, default on). |
| `gorsso-audit` | `AuditEventListener(Factory)` | Forwards events to `POST {url}/audit/events` with the `X-Audit-API-Key` header, asynchronously. |

The audit listener reads `KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_URL` and `..._API_KEY`. If those are unset, it falls back to
`AUDIT_SERVICE_URL`, `AUDIT_API_KEY` and `AUDIT_API_KEY_FILE`. With no URL, nothing is forwarded. The payload is unchanged from the
reference: `event_type, user_id, app_id, realm, performed_by, target, ip_address, details, severity`. The event mapping is in
`AuditMapping` and `AuditPayloads`:

- `LOGIN_ERROR` maps by selected credential type: `auth.mfa.otp_failed`, `auth.mfa.recovery_code_failed` or `auth.login.failed`.
- `UPDATE_TOTP` maps to `auth.mfa.totp_enrolled`, and `REMOVE_TOTP` to `auth.mfa.totp_removed`.
- Recovery-code `UPDATE_CREDENTIAL` and `REMOVE_CREDENTIAL` map to `auth.mfa.recovery_codes_configured` and `auth.mfa.recovery_codes_removed`.
- `UPDATE_CREDENTIAL_ERROR` for otp or recovery codes maps to `auth.mfa.enrol_failed`.
- Admin `USER` `ACTION|DELETE` on `users/<id>/credentials/<cid>` maps to `admin.mfa.credential_removed`.
- A realm-role mapping that names `mfa-exempt` maps to `admin.mfa.exempt_role_mapping`.
- A realm update that changes the effective mode maps to `admin.mfa.switch_observed`. The first update a node sees only primes its cache.
- `LOGIN` itself is not forwarded.

## 3. The `browser-totp` flow (exact; installer must match)

Top-level flow `browser-totp`, `basic-flow`, bound as the realm `browserFlow`. The executions are listed
depth-first in Keycloak's `GET .../flows/browser-totp/executions` order. *level* is the nesting depth that Keycloak reports.
Sub-flow display name is its alias. Every sub-flow is `basic-flow`.

| # | lvl | kind | provider / alias | requirement | tf prio | config alias: values |
|---|---|---|---|---|---|---|
| 0 | 0 | exec | `gorsso-mfa-step-up` | REQUIRED | 5 | |
| 1 | 0 | flow | `browser-totp main` | REQUIRED | 10 | |
| 2 | 1 | exec | `auth-cookie` | ALTERNATIVE | 10 | |
| 3 | 1 | exec | `auth-spnego` | DISABLED | 20 | |
| 4 | 1 | exec | `identity-provider-redirector` | ALTERNATIVE | 25 | |
| 5 | 1 | flow | `browser-totp Organization` * | ALTERNATIVE | 26 | |
| 6 | 2 | flow | `browser-totp Conditional Organization` * | CONDITIONAL | 10 | |
| 7 | 3 | exec | `conditional-user-configured` * | REQUIRED | 10 | |
| 8 | 3 | exec | `organization` * | ALTERNATIVE | 20 | |
| 9 | 1 | flow | `browser-totp forms` | ALTERNATIVE | 30 | |
| 10 | 2 | flow | `browser-totp password` | CONDITIONAL | 10 | |
| 11 | 3 | exec | `conditional-level-of-authentication` | REQUIRED | 10 | `browser-totp-loa1`: `loa-condition-level=1`, `loa-max-age=36000` |
| 12 | 3 | exec | `auth-username-password-form` | REQUIRED | 20 | |
| 13 | 2 | flow | `browser-totp 2FA` | CONDITIONAL | 20 | |
| 14 | 3 | exec | `conditional-user-role` | REQUIRED | 10 | `browser-totp-not-exempt`: `condUserRole=mfa-exempt`, `negate=true` |
| 15 | 3 | exec | `gorsso-condition-realm-attribute` | REQUIRED | 20 | `browser-totp-switch`: `attribute=mfa_totp_enforced`, `expected=true`, `when_unset=true`, `loa=2` |
| 16 | 3 | exec | `conditional-level-of-authentication` | DISABLED | 30 | `browser-totp-loa2`: `loa-condition-level=2`, `loa-max-age=36000` |
| 17 | 3 | flow | `browser-totp enrol` | CONDITIONAL | 40 | |
| 18 | 4 | exec | `gorsso-condition-credential` | REQUIRED | 10 | `browser-totp-no-otp`: `credential_type=otp`, `present=false` |
| 19 | 4 | exec | `auth-otp-form` | REQUIRED | 20 | |
| 20 | 4 | flow | `browser-totp enrol recovery codes` | CONDITIONAL | 30 | |
| 21 | 5 | exec | `gorsso-condition-credential` | REQUIRED | 10 | `browser-totp-no-recovery-codes`: `credential_type=recovery-authn-codes`, `present=false` |
| 22 | 5 | exec | `auth-recovery-authn-code-form` | REQUIRED | 20 | |
| 23 | 3 | flow | `browser-totp verify` | CONDITIONAL | 50 | |
| 24 | 4 | exec | `gorsso-condition-credential` | REQUIRED | 10 | `browser-totp-has-otp`: `credential_type=otp`, `present=true` |
| 25 | 4 | flow | `browser-totp 2FA methods` | REQUIRED | 20 | |
| 26 | 5 | exec | `auth-otp-form` | ALTERNATIVE | 10 | |
| 27 | 5 | flow | `browser-totp recovery` | ALTERNATIVE | 20 | |
| 28 | 6 | exec | `auth-recovery-authn-code-form` | REQUIRED | 10 | |
| 29 | 6 | exec | `gorsso-recovery-reenrol` | REQUIRED | 20 | |

\* The Organization rows are `"optional": "organization_login"`. Terraform includes them when
`enable_organization_login` (default true). The reference installer copies the requirement of the
`Organization` step from the realm's current `browser` flow, which may be `DISABLED`. It omits rows 5–8 when that
step (the feature) is absent.

Why the shape is like this (from mfa.tf):

- **Step-up before `main`.** The step-up has to run before the cookie authenticator. A REQUIRED sibling next to ALTERNATIVE ones makes
  Keycloak ignore the alternatives, so the ALTERNATIVE steps sit inside the `main` wrapper sub-flow.
- **Exemption first.** The exemption condition comes before the realm-attribute condition, because Keycloak stops at the first condition that fails.
- **Enrol and verify split.** The flow splits on whether the user has an OTP credential. Otherwise an unenrolled user falls through to the recovery branch.
- **The DISABLED LoA-2 condition is only a declaration.** It gives the max age for level 2. It is never evaluated.

Realm-side settings applied with the flow (base.tf / installer):

- `otpPolicyType=totp`, `HmacSHA1`, 6 digits, 30 s, look-ahead 1.
- Brute force: `failureFactor 5`, `waitIncrementSeconds=maxFailureWaitSeconds=900`.
- Attribute `acr.loa.map={"1":1,"2":2}`.
- Locale `rw` is added to `supportedLocales`.
- Events: listener `gorsso-audit` and user events enabled. If `enabledEventTypes` is non-empty, add `UPDATE_TOTP, REMOVE_TOTP,
  UPDATE_CREDENTIAL, UPDATE_CREDENTIAL_ERROR, REMOVE_CREDENTIAL, LOGIN, LOGIN_ERROR`. An empty list already means all types.
- Realm role `mfa-exempt`, with the description in `MfaConstants.EXEMPT_ROLE_DESCRIPTION`.
- Required actions `CONFIGURE_TOTP` (priority 10) and `CONFIGURE_RECOVERY_AUTHN_CODES` (priority 20): enabled, `defaultAction=false`.
- **The mode is written only when unset, and then as `off`.** The installer never turns enforcement on.
- Before anything else, the installer checks `/admin/serverinfo`: the 4 `gorsso-*` authenticators, the
  `gorsso-audit` listener, `auth-recovery-authn-code-form` and the `CONFIGURE_RECOVERY_AUTHN_CODES` action must all be present.
- **Binding comes last.** Bind `browserFlow=browser-totp` only after every execution and config exists.
- **Drifted flow.** If the flow exists but differs, `--check` reports the difference. A rebuild first binds `browser`, deletes the flow, recreates it and binds it again.

Admin API recipe (what the reference installer and `KeycloakProviderIntegrationTest` do), for each row in order:

1. Execution: `POST /authentication/flows/{parent}/executions/execution {"provider": id}`.
   Sub-flow: `POST .../executions/flow {"alias","type":"basic-flow","description","provider":"registration-page-form"}`.
2. Find the new level-0 row of `GET .../flows/{parent}/executions`. If its requirement differs, `PUT .../flows/{parent}/executions`
   with the new `requirement`.
3. If the row has a config: `POST /authentication/executions/{id}/config {"alias","config"}`.

To compare with a live realm, flatten `GET .../flows/browser-totp/executions` to `(level, kind, providerId | displayName, requirement)`.

### `mfa-flow.json` shape

```jsonc
{
  "schema_version": 1,
  "flow": {"alias": "browser-totp", "provider_id": "basic-flow", "top_level": true, "built_in": false, "description": "..."},
  "bind_as": "browserFlow",
  "loa_max_age_seconds": 36000,
  "realm_settings": { "otpPolicyType": "totp", ... },            // exact Admin API realm fields
  "realm_attributes": {"acr.loa.map": "{\"1\":1,\"2\":2}"},
  "initial_mode_if_unset": "off",
  "exempt_role": {"name": "mfa-exempt", "description": "..."},
  "required_actions": [{"alias","name","enabled","default_action","priority"}, ...],
  "events": {"listener": "gorsso-audit", "enabled_event_types": [...]},
  "required_server_providers": {"authenticator": [...], "eventsListener": [...], "required-action": [...]},
  "executions": [   // depth-first, == the table above
    {"index": 0, "level": 0, "parent": "browser-totp", "kind": "execution", "provider_id": "gorsso-mfa-step-up",
     "requirement": "REQUIRED", "priority": 5, "tf_resource": "step_up"},
    {"index": 1, "level": 0, "parent": "browser-totp", "kind": "flow", "alias": "browser-totp main",
     "flow_provider_id": "basic-flow", "requirement": "REQUIRED", "priority": 10, "tf_resource": "main"},
    {"index": 11, ..., "config": {"alias": "browser-totp-loa1", "values": {"loa-condition-level": "1", "loa-max-age": "36000"}}},
    {"index": 5, ..., "optional": "organization_login"}
  ]
}
```

`parent` is the alias of the containing flow, so the list can be applied in order. `tf_resource` is the
Terraform resource name in `mfa.tf` that the consistency test matches on.

## 4. Image (`keycloak/Dockerfile`)

`docker build -f keycloak/Dockerfile .` from the repo root. The build has four stages:

1. **Maven** (`maven:3.9-eclipse-temurin-21`) builds `mfa-policy` and `keycloak-extensions` and produces `gorsso-mfa.jar`.
   Pass `SKIP_TESTS=true` to skip the unit tests.
2. **Node** (`node:22-alpine` plus apk `maven`/`openjdk21-jdk`, which Keycloakify needs) runs `npm ci && npm run
   build-keycloak-theme` in `keycloak-theme/` and keeps `dist_keycloak/*.jar`. It drops `*-to-2[0-5].jar`.
   Pass `BUILD_THEME=false`, or leave `keycloak-theme/package.json` out of the context, to skip it.
3. **`kc.sh build`**: the providers, the theme jar and `configs/realms/*.json` are copied in first. The build sets
   `KC_DB=postgres`, `KC_FEATURES=organization,admin-fine-grained-authz:v2,recovery-codes` and
   `KC_HEALTH_ENABLED`/`KC_METRICS_ENABLED=true`. These are frozen into the image.
4. **Runtime**: `start --optimized --import-realm`, exposing 8080, 8443 and 9000. A `HEALTHCHECK` probes `:9000/health/ready` with bash `/dev/tcp`.

`keycloak/Dockerfile.dockerignore` is an allow-list that keeps `node_modules`, `target` and `dist*` out of the context.
The image was built locally, both with and without the theme. Both `gorsso-mfa.jar` and
`gorsso-keycloak-theme.jar` were registered at `kc.sh build`.

## 5. Tests

- `mfa-policy`: `mvn -B -f mfa-policy/pom.xml install` runs 133 tests.
  - `MfaPolicyTest` and `SwitchStateTest` are table-driven and port every case from the reference
    `PolicyTest.java` and from `tests/e2e/test_mfa_totp.py`: `TestSwitchReading`, `TestReason`,
    `TestPasswordGrantRefusal`, `TestBulkSkipRules`, `TestPerClient` and the `get_state` fields.
  - `MfaFlowDefinitionTest` checks the JSON invariants.
  - `MfaFlowTerraformConsistencyTest` ports `test_kc26_enable_mfa.py`. It parses `infra/terraform/mfa.tf` and
    asserts that the tree, priorities and configs equal the JSON. It is skipped when `infra/terraform` is absent.
- `keycloak-extensions`: `mvn -B -f keycloak-extensions/pom.xml package` runs 103 tests.
  - The reference `PolicyTest` passes unchanged, apart from `MfaRequirement` becoming `MfaPolicy`.
  - `AuditMappingTest` is an exhaustive event, admin-event and severity table.
  - `AuditEventListenerTest` checks exact payloads over mocked Keycloak models.
  - `ProvidersTest` covers the service registration and ids, the step-up, LoA and re-enrol decisions, and the HTTP
    forwarder against a local server (URL, header, JSON, failure without throwing).
- IT: `mvn -B -Pit -f keycloak-extensions/pom.xml verify` runs `KeycloakProviderIntegrationTest` (`@Tag("it")`, about 45–65 s).
  It uses testcontainers-keycloak with `quay.io/keycloak/keycloak:26.2`, the three features and the provider jar. The test:
  - asserts through `/admin/serverinfo` that the 4 authenticators, `gorsso-audit`, the recovery-code form and the action are present;
  - builds `browser-totp` from `mfa-flow.json` with the Admin API recipe above;
  - reads it back identically and binds it.

  It uses `target/gorsso-mfa.jar` when that is newer than the classes. Otherwise it assembles the same jar from the classes.

## 6. Gaps / not done here

- The wrapper side is not implemented here: the switch service, `PUT /realm/mfa`, the per-client endpoints, exemptions, the
  bulk-enrol job, the password-grant gate and the flow **installer**. Those are owned by others. They should use
  `MfaPolicy`, `SwitchState` and `mfa-flow.json`.
- There is no end-to-end browser login test through the flow (OTP enrolment, verify, acr=2, recovery re-enrol). The IT only proves that the
  providers load and the flow builds and binds.
- `AuditEventListenerFactory` uses Keycloak's internal SPI, as the reference did (KC-SERVICES0047 warnings at
  build). Re-run the IT when bumping Keycloak.
