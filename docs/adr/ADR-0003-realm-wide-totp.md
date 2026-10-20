# ADR-0003 — TOTP (2FA): realm mode, per-client requirement

| | |
|---|---|
| **Status** | **Proposed** (2026-09-30), **amended the same day** (§9: per-client requirement, which reverses §1.4). Implemented and verified on Keycloak 26.2.5 on a probe realm; not deployed. Accepting it means shipping the provider JAR in the 26.2 image (§7). |
| **Date** | 2026-09-30 |
| **Author** | MUVANDIMWE Anastase |
| **Deciders** | GoR SSO / RISA identity team |
| **Relates to** | `ADR-0001-single-realm-institution-model.md` (one realm — so one switch covers every institution), `ADR-0002-fgap-v2-migration.md` (26.2 is the target), `docs/implemented/mfa-totp.md` (the design in full) |
| **Affects** | `infra/terraform/mfa.tf`, `base.tf`, `keycloak/extensions/gorsso-mfa`, `src/app/core/mfa_policy.py`, `src/app/services/mfa_service.py`, `/auth/login`, `/oauth2/authorize-internal`, the Keycloak 26.2 image, `themes/*` |

---

## 1. Decision

**Every user of every OIDC client in `government-internal` presents a TOTP code
at login. One realm attribute, `mfa_totp_enforced`, turns the requirement on or
off. It is owned by the super admin, and the only exception is the `mfa-exempt`
role, which is granted with a recorded justification.**

The decision has eight parts:

1. **Keycloak enforces it, not the wrapper.** It is a conditional sub-flow in a
   new browser flow, `browser-totp`, bound as the realm's browser flow. An app
   that talks to Keycloak directly gets the same rule.
2. **The switch is a realm attribute read at login time.** A flip takes effect
   at the next login with no restart. Unset reads as **on**, so enforcement is
   the default.
3. **A custom Keycloak provider reads it** (§3). Keycloak 26.2 has no built-in
   condition that reads a realm attribute.
4. **No per-client opt-out.** *(Superseded by §9.)* No client attribute is consulted. The only
   exception is per person (`mfa-exempt`), granted only through
   `POST /realm/mfa/exemptions`, which requires a reason.
5. **Offline TOTP only**: SHA1, 6 digits, 30 s, with recovery codes as the
   fallback. The deployment is air-gapped, so there is no SMS, push or email
   OTP (§5).
6. **`acr` says what happened**: `1` for password only, `2` when a code was
   presented. Applications check `acr`, not the switch.
7. **The wrapper refuses password grants for MFA-bound people** while the switch
   is effective (§4). This contradicts the brief's "no wrapper change needed",
   deliberately.
8. **Everything is code.** The flow, role, required actions, OTP policy,
   lockout and acr map are Terraform. The only thing set at runtime is the
   switch, which Terraform writes once and then ignores.

> **In one line (as first decided):** *one realm attribute, read by Keycloak on
> every login, decides whether everyone needs a code; the only way out is a
> named, justified exemption.* **§9 amends this:** the realm attribute became a
> three-way mode, one of which lets each client decide.

## 2. Context

Institutions share one realm (ADR-0001), so there is no per-ministry place to
put a second factor: it is realm-wide or nothing. The brief also requires the
requirement to be switchable by the super admin without a Keycloak restart, and
every switch change to be audited with its actor.

## 3. Why custom providers, and why four

Keycloak 26.2 ships conditions on user role, user attribute, user configured,
client scope, sub-flow executed and level of authentication, but **none that
reads a realm attribute**. Keycloak's own step-up mechanism (the "Level of
Authentication" condition) cannot serve as the gate either. With no
`acr_values` requested, which is how every client here authenticates, a level-2
condition evaluates to false once level 1 is reached, and the OTP step never
runs.

So `keycloak/extensions/gorsso-mfa` provides the switch condition. Verification
on 26.2.5 then forced three more providers. Each answers a failure observed on
the real server:

| Provider | The failure it answers |
|---|---|
| `gorsso-condition-realm-attribute` | the switch; it also records level 2, so `acr=2` |
| `gorsso-condition-credential` | with OTP Form and recovery as plain ALTERNATIVES, a never-enrolled user fell through to the recovery branch |
| `gorsso-recovery-reenrol` | deleting the lost OTP inside the flow re-routed the same login into an endless recovery-code prompt; the removal now waits for the flow to finish |
| `gorsso-audit` | Keycloak's events never reached the hash-chained audit trail (the realm only had `jboss-logging`) |

**Cost:** these providers implement internal SPI and "may change without
notice". Every Keycloak upgrade must re-run the probe described in the design
doc. The flow depends on engine behaviour: how ALTERNATIVEs fall through, that
conditions short-circuit, and when callbacks fire.

## 4. The password-grant door

`/auth/login` (a password grant) plus `/oauth2/authorize-internal` (which turns
the resulting token into a code) is a documented integration path. A password
grant cannot carry a second factor, so leaving that path open would make the
requirement optional for any client with direct grants enabled. While the
switch is *effective* (the attribute is on **and** `browser-totp` is bound),
both refuse the token with 403 `mfa_required`, unless the person is exempt or
the token already has `acr=2`.

**Considered: changing the direct-grant flow in Keycloak.** Rejected by the
brief, and it would break service integrations wholesale. What remains open is
a client calling Keycloak's own token endpoint with a password. The control
there is `direct_access_grants = false`, which is already Terraform's default
for institution clients.

**Consequence:** integrations built on that login UI stop working for
non-exempt users when the switch goes on. They must move to
`/oauth2/authorize` with PKCE first (§8).

## 5. OTP algorithm

SHA1 is kept because every offline authenticator app implements it, and Google
Authenticator has historically ignored `algorithm=` in the `otpauth://` URI.
With no fallback factor in an air-gapped network, a mismatch would lock people
out. HMAC-SHA1 is not weakened by SHA1's collision attacks. Moving to SHA256
is supported without breaking anyone: each credential keeps the algorithm it
was enrolled with. The procedure is in the design doc.

## 6. Consequences

**Positive**
- **Enforcement is in Keycloak**, for every client, with no wrapper in the
  path.
- **One switch, audited twice**: by the wrapper with its actor, and by the
  Keycloak listener when the attribute changes by any other route.
- **`acr` gives applications a verifiable signal** that MFA happened.
- **Recovery needs no helpdesk**: a person with a code re-enrols a new device
  themselves.

**Negative / accepted**
- **A custom JAR must be built on a workstation** and baked into the image. The
  air-gapped host cannot build it.
- **Recovery codes are a preview feature in 26.2.5** and must be enabled
  explicitly.
- **The OTP label in apps is the realm's display name.** Keycloak has no
  separate issuer setting.
- **Password-grant integrations break** while the switch is on (§4).
- **New users are asked to enrol even when the switch is off**, because the
  default required actions do not depend on the switch.
- **A recovery-code login deletes the lost device's credential.** This is
  configurable per execution.

## 7. What acceptance requires

1. **Image:** the deploy bundle's `Dockerfile.keycloak26` copies
   `gorsso-mfa.jar` into `/opt/keycloak/providers/` before `kc.sh build`, adds
   `recovery-codes` to its features, and sets the listener's audit URL and key
   at runtime.
2. **Terraform:** `enable_mfa_totp = true` is applied to the 26.2 realm, with
   the 26 theme names and `realm_organizations_enabled = true`.
3. **Review:** a native speaker reviews the Kinyarwanda login-page text.
4. **Migration:** password-grant integrations are moved to PKCE (§8).
5. **Communication:** staff are told, and the switch is turned on.
   Procedure: `docs/runbooks/enable-mfa-totp.md`.

## 8. Revisit when

- **Keycloak ships a realm-attribute or credential condition.** Replace the
  matching provider.
- **Recovery codes leave preview.** Drop the explicit feature flag.
- **Any integration still depends on `/auth/login` at switch-on time.** Decide
  between migrating it and a time-boxed exemption for its users.
- **WebAuthn becomes available on staff devices.** Add it as a level-2
  alternative beside OTP Form.

## 9. Amendment (2026-09-30) — per-client requirement

**Decision.** TOTP can be required for some clients and not others. §1.4 ("no
per-client opt-out") is reversed; everything else stands.

1. **A realm mode replaces the on/off switch.** The realm attribute
   `mfa_totp_mode` takes one of three values:
   - `off`: nobody is asked for a code;
   - `all`: every client requires it, as first decided;
   - `per_client`: each client decides.

   An unset mode reads the original `mfa_totp_enforced` (`false` means off,
   otherwise all), so a realm set up before this amendment is unchanged. An
   unrecognised value reads as `all`, which fails closed.
2. **Each client's setting is `sso.client.mfa_required`.** It is read only in
   `per_client` mode. **Unset means required**: a client nobody configured is
   protected, and opting a client out is a deliberate, audited act.
3. **Who changes what.**
   - The mode, and a client in either direction: **super admin**.
   - A delegated (institution) admin may turn TOTP **on** for their institution's
     own `<slug>-*` apps, **never off**, and never on a cross-cutting app.
   - An institution can raise the bar on its apps but cannot lower it.
4. **SSO steps up.** A new first step in the flow, `gorsso-mfa-step-up`, raises
   the requested level to 2 for clients that require TOTP. A session from a
   client that did not ask for a code therefore steps up to the code when the
   person opens one that does, instead of being admitted at level 1.
   Keycloak's own per-client `minimum.acr.value` would do the same, but every
   client would have to be rewritten whenever the mode changed.
5. **Bulk enrolment only for `all`.** In `per_client` it would force enrolment
   on people who only use clients that need no code. The flow enrols each
   person at their first login to a client that requires one.

**Considered:**
- **A per-client setting with no realm mode.** Rejected: it loses the one-step
  "off everywhere" and "on everywhere" a super admin needs in an incident or a
  roll-out.
- **Delegated admins turning TOTP off on their own apps.** Rejected: it would
  make the institution the one to decide whether its staff's accounts are
  protected.

**Consequences:**
- **Tokens differ per client.** Tokens for different clients can carry
  different `acr` values. An application must check `acr` itself, as §1.6
  already said.
- **Password-grant refusal is per client.** The wrapper's refusal of password
  grants (§4) now follows the client's own requirement, matching Keycloak.
- **One more custom provider** (the step-up), with the same internal-SPI caveat
  as §3.
- **More to review.** "Which clients don't require 2FA" is now a question
  worth reviewing. The console's clients list shows it per row, and every
  change is audited as `admin.mfa.client_requirement_changed`.

Verified on 26.2.5, directly and through the wrapper:
- step-up from a no-2FA session;
- exempt people moving between clients;
- `all` overriding a client's "not required";
- the legacy fallback;
- the delegated admin's refusals;
- a Terraform plan leaving the client attribute alone.

