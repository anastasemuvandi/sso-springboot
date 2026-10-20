# Runbook: realm-wide TOTP (deploy, switch on, operate)

| | |
|---|---|
| Applies to | realm `government-internal`, Keycloak 26.2 only |
| Automation | `keycloak/Dockerfile` (gorsso-mfa provider baked in), `infra/terraform/mfa.tf` (`enable_mfa_totp`), wrapper `mfa-install` (offline hosts), `PUT /realm/mfa`, `make mfa-enrol` |
| Design | `docs/implemented/mfa-keycloak.md` (flow table, provider ids, `mfa-flow.json`) |

The realm attribute `mfa_totp_mode` sets the mode:

- `off`: no client asks for a code.
- `per_client`: each client decides via `sso.client.mfa_required`. Unset or blank means required; only `false` opts out.
- `all`: every client asks for a code.

Unset falls back to the legacy `mfa_totp_enforced` (unset means `all`); an unknown value means `all`. The same rule lives in the `mfa-policy` module, shared by the Keycloak provider and the wrapper. The mode does nothing until the `browser-totp` flow is bound (section 2); before that the wrapper refuses any mode except `off` (409).

## 1. Before you start

- [ ] The Keycloak image is built from `keycloak/Dockerfile` (it contains `gorsso-mfa.jar` and has `recovery-codes` in `KC_FEATURES`).
- [ ] List integrations that use the wrapper's password grant (`POST /auth/login`, `/oauth2/authorize-internal`). Once TOTP applies they get 403 `mfa_required` for non-exempt users. Move them to `/oauth2/authorize` with PKCE, or plan time-boxed exemptions. The audit trail shows who still uses that door.
- [ ] Kinyarwanda strings in `keycloak-theme` reviewed by a native speaker.
- [ ] Staff told what is coming and how to enrol.
- [ ] Break-glass exemptions decided, each with a written reason.

## 2. Deploy (once)

### 2.1 Image

```bash
docker build -f keycloak/Dockerfile -t $CI_REGISTRY_IMAGE/keycloak:<tag> .   # from the repo root
```

CI builds and pushes it. Air-gapped hosts pull the image; they never build it. Features are build-time options: never set `KC_FEATURES` in a staging/prod compose file, `start --optimized` refuses to boot (the overlays use `environment: !override` for that reason).

### 2.2 Runtime settings

On the Keycloak service (the staging/prod overlays already set these):

```bash
KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_URL=http://audit-service:8001
KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_API_KEY=<same value as AUDIT_API_KEY>
```

The listener also falls back to `AUDIT_SERVICE_URL` / `AUDIT_API_KEY` / `AUDIT_API_KEY_FILE`. With no URL, events are not forwarded; logins still work.

Local dev stack: add the overlay, which sets the listener and features for `start-dev`:

```bash
docker compose -p gorsso -f deploy/docker-compose.base.yml -f deploy/docker-compose.apps.yml \
  -f deploy/docker-compose.mfa.yml up -d
```

### 2.3 Build the flow

Option A, Terraform (normal path). In the environment's tfvars:

```hcl
enable_mfa_totp             = true
realm_organizations_enabled = true
mfa_totp_mode_initial       = "off"   # only used when Terraform creates the realm
```

```bash
make tf-plan  TF_ENV=<env>
make tf-apply TF_ENV=<env>
```

Terraform never changes the mode afterwards (`ignore_changes` on `mfa_totp_mode` and `mfa_totp_enforced` in `base.tf`). On an existing realm with no mode set, binding the flow means `all`. If you do not want enforcement the moment the flow binds, set `off` first; `off` is always accepted:

```bash
curl -X PUT "$SSO/realm/mfa" -H "Authorization: Bearer $SUPER_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"mode":"off"}'
```

Option B, offline host with no Terraform. Run the installer, which applies the same flow and realm settings through the Admin API from `mfa-flow.json`, leaves the mode `off` when unset, and binds the flow last:

```bash
java -jar sso-wrapper/target/sso-wrapper-*.jar mfa-install            # apply
java -jar sso-wrapper/target/sso-wrapper-*.jar mfa-install --check    # report drift, change nothing
```

Never use both options on one realm. A test asserts the installer and `mfa.tf` describe the same flow.

What either option sets: the `browser-totp` flow bound as `browserFlow`, OTP policy (TOTP, HmacSHA1, 6 digits, 30 s), brute force 5 failures / flat 900 s, `acr.loa.map={"1":1,"2":2}`, locale `rw`, the `gorsso-audit` event listener, realm role `mfa-exempt`, required actions `CONFIGURE_TOTP` and `CONFIGURE_RECOVERY_AUTHN_CODES`.

### 2.4 Verify the deploy

- [ ] `GET /realm/mfa` returns `"browser_flow": "browser-totp"` and `"flow_ready": true`.
- [ ] Keycloak console, Authentication, `browser-totp` matches the table in `docs/implemented/mfa-keycloak.md`. Do not edit it there.
- [ ] Realm settings show ACR to LoA mapping `1 -> 1`, `2 -> 2`.
- [ ] No client has a browser-flow override (that client would skip TOTP entirely).
- [ ] Keycloak log shows the `gorsso-audit` listener forwarding to the audit URL.

## 3. Turn it on

1. Announce the date.
2. Choose the mode. For `per_client`, first mark the clients that must not require a code: `PUT /client/clients/<id>/mfa {"required": false}` (super admin). Every other client requires one.
3. As a super admin: console Settings, Two-factor authentication, or `PUT /realm/mfa {"mode":"per_client"|"all"}`.
4. The first time the mode becomes `all`, the wrapper assigns `CONFIGURE_TOTP` in the background to every user without an OTP credential (skipping disabled users, service accounts and exempt users) and audits the counts. If errors were reported, re-run by hand; it is idempotent and resumable:

   ```bash
   make mfa-enrol                                                    # checkpointed, resumable
   java -jar sso-wrapper/target/sso-wrapper-*.jar mfa-enrol --dry-run  # counts only
   ```

5. Check: `GET /realm/mfa` shows the mode and `"effective": true`; a test account is asked to enrol and its token carries `acr: "2"`; the audit trail has the mode change with actor, old and new value.

It takes effect at the next login. Existing sessions are not ended.

## 4. Turn it off

`PUT /realm/mfa {"mode":"off"}`. Next logins are password only (`acr: "1"`). OTP credentials and per-client settings are kept. Bulk enrolment does not run again. Pending `CONFIGURE_TOTP` actions remain until removed from the users.

Full removal: switch off, set `enable_mfa_totp = false`, apply, and confirm the realm's browser flow is `browser`. Keycloak will not delete a bound flow.

## 5. Day to day

### Who may change a client's setting

| | Turn on | Turn off |
|---|---|---|
| Super admin | `PUT /client/clients/<id>/mfa {"required": true}` | same with `false` |
| Institution admin | own `<slug>-*` apps: `PUT /institution-admin/apps/<id>/mfa {"required": true}` | never (403) |

Cross-cutting apps are refused to institution admins both ways. Every change is audited with actor, client, old and new value.

### Exemptions

```bash
POST   /realm/mfa/exemptions   {"user_id": "<id>", "reason": "<who asked, why, until when>"}   # 15..500 chars
GET    /realm/mfa/exemptions
DELETE /realm/mfa/exemptions/<user_id>
```

Super admins only. Granting clears a pending `CONFIGURE_TOTP`. Never assign `mfa-exempt` in the Keycloak console: no reason is recorded and the listener flags it (`admin.mfa.exempt_role_mapping`). Review exemptions quarterly.

### Lost phone

- With recovery codes: the user signs in, chooses "Try another way", uses a code, and enrols the new phone. The old OTP credential is removed (`auth.mfa.recovery_code_used`, `auth.mfa.totp_removed`).
- Without: verify the person's identity, then delete their OTP credential (Admin API or console, audited as `admin.mfa.credential_removed`). They enrol at next login.

### Lockouts

Five wrong passwords, codes or recovery codes lock the account for 15 minutes; Keycloak answers 403.

```bash
GET    $KC/admin/realms/government-internal/attack-detection/brute-force/users/<id>
DELETE $KC/admin/realms/government-internal/attack-detection/brute-force/users/<id>
```

Repeated `auth.mfa.otp_failed` from an unfamiliar IP means the password is known. Reset it.

### "The app says I did not use two-factor"

The app reads `acr`. `"1"` means password only: exempt user, switch off, or password grant. Apps requesting `acr_values` use `1` and `2`.

## 6. Where to look

| Question | Where |
|---|---|
| On and effective? | `GET /realm/mfa` |
| Who changed it? | audit `admin.mfa.*` |
| Who is exempt? | `GET /realm/mfa/exemptions` |
| Failed codes, recoveries, enrolments | audit `auth.mfa.*` |
| No `auth.mfa.*` at all | Keycloak log for `gorsso-audit`; section 2.2 |

## 7. Upgrading Keycloak

The providers use internal SPI. Before moving past 26.2.x, bump `keycloak.version` in the root `pom.xml`, rebuild, and run `mvn -B -Pit -f keycloak-extensions/pom.xml verify` (`KeycloakProviderIntegrationTest`) against the new version.
