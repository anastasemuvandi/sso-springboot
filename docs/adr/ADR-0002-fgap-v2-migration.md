# ADR-0002 — Delegated admin on Keycloak 26 FGAP v2

| | |
|---|---|
| **Status** | **Accepted** (2026-08-21) · **implemented in the repository**. The platform runs **Keycloak 26.2** only. The spike proved the model end to end (`docs/spikes/kc26-fgap-v2-orgs.md`) and the three findings it produced are folded in. Remaining work is tracked in §12. |
| **Date** | 2026-08-19 (proposed) · **2026-08-21 (accepted)** |
| **Author** | MUVANDIMWE Anastase |
| **Deciders** | GoR SSO / RISA identity team |
| **Relates to** | `ADR-0001-single-realm-institution-model.md` (the single-realm decision this ADR keeps), `docs/implemented/delegated-permissions.md`, `docs/test-plans/delegated-permissions.md` |
| **Affects** | Keycloak version, `configs/delegated-permissions/templates.json` (as input, not as runtime policy), `infra/terraform`, `src/app/core/keycloak_clients.py`, `src/app/core/institution_context.py`, `src/app/services/institution_admin_service.py`, `make delegated-check`, CI |

---

## 1. Decision

**Keycloak 26.2 is the platform's version. Delegated institution administration is enforced by
Keycloak's Fine-Grained Admin Permissions v2 (FGAP v2), and the wrapper stops being the only
thing that holds the boundary.**

Six sub-decisions, each expanded below:

1. **One realm, unchanged.** Institutions remain top-level groups in `government-internal`.
   ADR-0001 §1 is **not** amended. FGAP v2 is a within-realm mechanism; adopting it
   strengthens the single-realm decision rather than reopening it (§4).
2. **`templates.json` stays the source of truth.** It changes role: today it is read at
   runtime by the wrapper; afterwards it is *compiled* into Keycloak permission objects by
   Terraform. The file itself, its validator, and its `{slug}` rendering survive (§5).
3. **The `inst:<slug>:*` roles are unchanged.** They stop being regex-parsed grant markers
   and become the subject of Keycloak **role policies**. The token surface for downstream
   apps does not change at all.
4. **The wrapper calls Keycloak as the caller, not as its service account** for
   `/institution-admin/*`. Without this step FGAP v2 has no effect whatsoever (§6, Step 7) —
   this is the single most important implementation detail in this ADR.
5. **The guardrail stays.** A `realm-management` role still overrides FGAP v2 policies, so
   `forbidden_client_roles: {"realm-management": ["*"]}` remains correct and necessary (§8).
6. **Nothing is created by clicking.** Permission objects are Terraform-managed, generated
   from the same registry and templates that already generate the roles and clients (§7).

> **In one line:** *the institution boundary moves from wrapper Python into Keycloak's own
> authorization engine, using the same templates, the same roles, and the same realm.*

---

## 2. Context

### 2.1 The wrapper-only model

Without fine-grained admin permissions Keycloak has exactly two kinds of administrator: a full
administrator, or nobody. There is no native way to say "this person may manage only MINAGRI
staff." ADR-0001 §4 therefore delivered delegation in the **wrapper**:

| Layer | Where it lives |
|---|---|
| Policy | `configs/delegated-permissions/templates.json` — three tiers, written once with `{slug}` and rendered per institution |
| Policy engine | `src/app/core/delegated_permissions.py` — pure policy, no FastAPI, no Keycloak |
| Authority | Realm role `inst:<slug>:admin\|hr\|auditor`; the role **is** the grant |
| Request gate | `require_institution_admin` → `InstitutionContext` → `assert_capability` / `assert_role_in_namespace` / `assert_client_in_namespace` |
| Mandatory filter | `src/app/services/institution_admin_service.py`, re-asserting capability *and* scope in the service layer |
| Provisioning | `infra/terraform` renders `inst:<slug>:*` from the templates; `make delegated-apply` |
| Audit | `make delegated-check`, gating the `config-safety` CI job |

This works, and it is well tested. It has one structural property that no amount of testing
removes: **Keycloak never sees the local admin.** Every write is executed by the wrapper's
service account (`sso-client`), which holds `manage-users`, `manage-authorization` and more,
self-healed at every start by `reconcile_service_account_roles()`. Containment exists only
because every delegated path goes through `/institution-admin/*`.

That is why the model needs two compensating controls, both already in place:

- **The guardrail** — a local admin must hold no `realm-management` role at all, not even
  `view-users`; enforced at config load, at request time (403), and in the offline audit.
- **The unconditional `isolation` CI job** — not path-filtered and not env-gated, because the
  boundary is ordinary Python that a distant refactor can weaken.

### 2.2 What changed

Keycloak 26.2 ships **FGAP v2**, which expresses scoped administration *inside* a realm as
first-class realm data, evaluated by the Admin REST API itself. The capability vocabulary this
project chose in August 2026 — `view-members`, `manage-members`, `manage-membership`,
`map-roles`, `view-clients`, `manage-clients` — is Keycloak's own FGAP vocabulary, chosen
deliberately so that "if this platform ever moves to native FGAP, these templates translate
1:1 instead of being re-invented" (`templates.json`, header comment). This ADR is that
translation.

### 2.3 Why FGAP v1 was never the middle road

FGAP **v1** is a preview feature, and there is a sharper reason to avoid it: v1 grants are layered *on top of* `realm-management` client
roles — you assign the delegated admin `query-users`/`view-users`, then narrow them with
per-group and per-client permission objects. That is precisely what our guardrail declares
forbidden, and for a good reason: those roles let a local admin read the whole realm and
defeat the mandatory institution filter. v1 would have required breaking our own invariant.
v2 grants permissions directly, without a realm-management foothold, which is what makes it
compatible with the design we already have.

---

## 3. What FGAP v2 is

Four building blocks. Everything else is a combination of them.

**Resource — the "which thing."** The object being protected: a group (`/minagri`), a role
(`inst:minagri:staff`), a client (`minagri-kubaka-web`), a user. A permission may also target
"all groups" / "all clients" instead of naming them.

**Scope — the "what action."** A single action Keycloak defines for that resource type. You do
not invent scopes.

| Resource type | Scopes (indicative — confirm against the installed 26.x docs) |
|---|---|
| Groups | `view`, `manage`, `view-members`, `manage-members`, `manage-membership` |
| Users | `view`, `manage`, `map-roles`, `manage-group-membership`, `impersonate` |
| Clients | `view`, `manage`, `configure`, `map-roles`, and others |
| Roles | `map-role`, `map-role-composite`, `map-role-client-scope` |

**Policy — the "who."** Most often a **role policy** ("anyone holding role X"). Group, client,
user and aggregate policies also exist.

**Permission — the sentence.** `resource(s) + scope(s) + policy`. For example:

> *Anyone holding `inst:minagri:admin` may `view-members`, `manage-members` and
> `manage-membership` on the group `/minagri`.*

**Default deny.** If no permission allows an action, the action is refused. This matches the
fail-closed behaviour the delegated surface already has (a missing or unsafe template config
makes `/institution-admin/*` answer 503).

**Where it lives.** Enabling admin permissions is a **per-realm** switch
(`adminPermissionsEnabled`) that materialises one **`admin-permissions` client** in that
realm, acting as the authorization resource server for that realm's Admin API. We would enable
it on `government-internal` only.

---

## 4. Realm topology — explicitly unchanged

FGAP v2 does **not** reopen ADR-0001 §1, and two proposals in particular are rejected here so
they are not re-litigated later.

### 4.1 Rejected: one realm per institution

FGAP v2 is a within-realm mechanism. Its resources are one realm's users, groups, clients and
roles; there is no cross-realm resource type. Under realm-per-institution the fine-grained
machinery would be idling, because the only admin you can grant on a dedicated realm is a full
realm admin — the exact broad power ADR-0001 §2 called out as disqualifying. Every other cost
in ADR-0001's comparison table (broken cross-institution SSO, duplicate accounts, IPPIS
routing, N realms to patch, split audit) is untouched by FGAP v2.

Concretely, in this codebase, N realms means N of everything: `jwt_verify.py` pins the issuer
to `KEYCLOAK_LOCAL_ISSUER/realms/<realm>` and requires `azp` to be a clientId registered in
that realm, so token verification, per-client default-deny access policies,
`reconcile_service_account_roles()`, the audit stream and `configs/realms/*.json` all fan out
per institution. A civil servant transferring MINAGRI → MINISANTE becomes delete-and-recreate,
which changes their `sub` and breaks every downstream app that stored it as a foreign key.

### 4.2 Rejected: "`government-internal` as a master realm with institutional realms under it"

Keycloak realms are **flat, mutually isolated siblings**. There is no parent/child
relationship, no inheritance of users, groups, sessions or roles, and no way to scope one
realm by another. `master` is a reserved realm created at install; this deployment already has
it, and it holds the bootstrap Keycloak admin account that `keycloak_clients.py:58`
authenticates against (`user_realm_name="master"`). `government-internal` is a managed realm,
a peer of `government-citizens` and `government-admin`, and cannot become `master`.

The only administrative link between realms is master-realm `<realm>-realm` client roles —
whole-realm reach, granted from the realm that governs everything, and **not** refined by
FGAP v2. The only federation link is IdP brokering, which produces separate accounts in
separate realms, not one identity.

```
  What Keycloak has                     What "master + institutional realms" implies
  ─────────────────                     ────────────────────────────────────────────
  master                                        master
  government-citizens                          /   |   \            ← no such nesting
  government-internal                    minagri minisante minijust    exists
  government-admin
  (four flat, isolated realms)
```

### 4.3 The mental model to keep

> **Realm = population and security tier. Institution = group inside a realm.
> FGAP v2 = scoped admin inside a realm.**

Three independent axes. ADR-0001 settled the first as a premise and decided the second; this
ADR touches only the third.

### 4.4 Effect on the ADR-0001 §6 exception rule

The exception rule (a legally mandated separate realm for one institution) stands unchanged,
and its list stays empty. FGAP v2 makes condition 3 — *logical controls have been formally
found inadequate* — **harder** to satisfy, because it moves the institution boundary out of
our Python and into Keycloak's authorization engine.

---

## 5. Mapping: `templates.json` → FGAP v2

The translation is mechanical, which was the point of choosing the vocabulary in advance.

| Template field | FGAP v2 equivalent |
|---|---|
| `grant_role: inst:{slug}:admin` | **Role policy** on that realm role |
| `members.group: /{slug}` + `view-members` / `manage-members` / `manage-membership` | **Groups** permission on that group, with those scopes |
| `capabilities: map-roles` + `roles.allow: inst:{slug}:*` | **Roles** permission, scope `map-role`, listing the institution's roles |
| `roles.deny: inst:{slug}:admin` | Expressed by **omission** — the role is simply not listed |
| `capabilities: view-clients` / `manage-clients` + `clients.allow: {slug}-*` | **Clients** permission, scopes `view` / `manage`, listing the institution's clients |
| `assignments.by_institution` (withholding a tier) | Do not create that tier's policy/permissions for that institution |
| Guardrail | **Not expressible in FGAP v2** — stays in the wrapper and in the audit (§8) |

### 5.1 Worked example — MINAGRI, `institution-admin` tier

Template (rendered for `minagri`):

```json
"grant_role": "inst:minagri:admin",
"capabilities": ["view-members","manage-members","manage-membership",
                 "map-roles","view-clients","manage-clients"],
"members":  { "group": "/minagri", "include_subgroups": true },
"roles":    { "allow": ["inst:minagri:*"], "deny": ["inst:minagri:admin"] },
"clients":  { "allow": ["minagri-*"], "deny": [] }
```

Becomes, in the `admin-permissions` resource server of `government-internal`:

| Object | Content |
|---|---|
| Policy `minagri-admins` | Role policy → `inst:minagri:admin` |
| Permission `minagri-members` | Resource: group `/minagri` (+ its subgroups — see §9.1) · Scopes: `view-members`, `manage-members`, `manage-membership` · Policy: `minagri-admins` |
| Permission `minagri-roles` | Resources: `inst:minagri:hr`, `inst:minagri:staff`, `inst:minagri:auditor` — **not** `inst:minagri:admin` · Scope: `map-role` · Policy: `minagri-admins` |
| Permission `minagri-clients` | Resources: `minagri-kubaka-web`, … · Scopes: `view`, `manage` · Policy: `minagri-admins` |

### 5.2 Two semantic differences to hold on to

**Patterns become enumerations.** `inst:minagri:*` is matched per request today by
`fnmatchcase`, so a role created tomorrow is automatically in scope. An FGAP v2 permission
references concrete resources, so a new role or client is *not* in scope until the permission
list is updated. This is acceptable here only because Terraform already creates those roles and
clients from the registry and the templates — the same loop updates the permissions. It also
means a role created by hand in the console silently does not work, which reinforces the
existing "never click in the console" rule.

**Denies become absences.** `roles.deny` is an active rule today, and `merge_grants()`
guarantees a deny in any tier wins over an allow in any other. Under FGAP v2 the same outcome
is achieved by not listing the role — equivalent in effect, weaker as an invariant, because a
mistake of omission is silent. `_validate_template()` and `make delegated-check` are therefore
kept, not retired.

---

## 6. Implementation, step by step

### Step 1 — Run Keycloak 26.2

Keycloak 26.2 on a greenfield database, with realm imports in the 26 format (no `keycloak`
account/admin/email themes). Land this **before** any permission work.

### Step 2 — Enable the server feature

Start Keycloak with the FGAP v2 feature enabled, e.g. `KC_FEATURES=admin-fine-grained-authz:v2`.
Confirm the exact flag name and whether the feature is still *preview* in the chosen version —
for a government platform that determines the support posture (§9.3).

### Step 3 — Enable admin permissions on `government-internal`

Realm-level switch (`adminPermissionsEnabled`). Keycloak creates the `admin-permissions`
client in that realm; it is a container for rules, not a login client. Leave
`government-citizens` and `government-admin` untouched.

### Step 4 — Keep the existing roles

`inst:<slug>:admin|hr|auditor` already exist and are already Terraform-managed. No new roles.
Only their meaning changes: from "a marker the wrapper parses" to "the subject of a policy."

### Step 5 — Create one policy per tier per institution

Role policy → the tier's grant role. Generated, never hand-made.

### Step 6 — Create the permissions

As per the mapping in §5, honouring `assignments.by_institution` so a withheld tier produces
no objects.

### Step 7 — Call Keycloak as the caller *(the step that makes or breaks this)*

`app/core/keycloak_clients.py:keycloak_admin()` returns a client authenticated as the
wrapper's service account (`sso-client`), which is a full administrator. If `/institution-admin/*`
keeps using it, **Keycloak will see the service account on every request and allow everything**
— the new permission objects would never be consulted, and the entire migration would be
decorative.

So the wrapper needs a second factory alongside the existing one — conceptually
`keycloak_admin_as_caller(access_token)` — used **only** by the delegated surface, building a
per-request admin client from the caller's own bearer token. The service-account factory stays
for everything else (reconciliation, platform-admin endpoints, IPPIS sync). The existing rule
that the admin client is never cached applies to both.

A welcome side effect: Keycloak's own admin event log then records the actual civil servant
instead of recording `sso-client` for every administrative action in the country.

*To verify during the spike:* how the pinned `python-keycloak` accepts a pre-obtained bearer
token, and which client the token must originate from for the Admin API to accept it.

### Step 8 — Test with a real person, including the bypass test

Log in as `minagri.admin` and confirm:

1. lists MINAGRI staff, and only MINAGRI staff — including departmental subgroup members;
2. can assign `inst:minagri:staff`; cannot assign `inst:minagri:admin`;
3. cannot see MINISANTE at all, not even a count;
4. cannot assign `admin` / `super_admin` / any `realm-management` role;
5. **a direct call to the Keycloak Admin API, bypassing the wrapper entirely, is refused.**

Item 5 is the whole point. Today it would succeed. After FGAP v2 it must return 403 from
Keycloak itself. Every scenario in `docs/test-plans/delegated-permissions.md` keeps its
negative control; item 5 is added to each.

### Step 9 — Generate everything from Terraform

See §7. Onboarding an institution stays "edit `configs/institutions.yaml`, run apply."

### Step 10 — Keep the guardrail, and grow the audit

The guardrail is unchanged (§8). `make delegated-check` gains a second job: diff the permission
objects living in Keycloak against what `templates.json` says they should be, and exit non-zero
on drift. Today it audits *who holds which role*; afterwards it must also audit *what the rules
say*.

---

## 7. Terraform

> **Update 2026-08-19 — built, applied and verified.** `infra/terraform/fgap_v2.tf`
> creates the whole permission graph and was applied against a Keycloak 26.2.5
> spike into a fresh realm, after which the acceptance test passed **9/9** against
> Terraform-created objects. The sketch below is superseded by the real file and
> the confirmed argument names in `docs/spikes/kc26-fgap-v2-orgs.md` §8. Three
> things that section records and this one did not anticipate: the plan is
> **generated** (`make fgap-plan`) rather than computed in HCL, because the
> grouping in §5 is not expressible readably; apply needs **`-parallelism=1`** or
> it 409s on a race; and `base.tf`'s hard-coded themes stopped realm creation on
> KC 26 entirely — a blocker that had nothing to do with permissions.

The provider is already pinned at `~> 5.0` in `infra/terraform/versions.tf`, and version 5.9.0
— the copy vendored under `infra/terraform/.terraform/` — carries the FGAP v2 surface:

- `admin_permissions_enabled` on the realm resource (Step 3);
- `keycloak_group_admin_permissions`, `keycloak_users_admin_permissions`,
  `keycloak_role_admin_permissions`, `keycloak_openid_client_admin_permissions` (Steps 5–6).

Shape of the addition to `infra/terraform/institutions.tf` — one more loop over inputs
`locals.tf` already reads (`institutions.yaml` × `templates.json`):

```hcl
# Illustrative — argument names to be confirmed against the provider docs for the
# version in use. The inputs are the ones locals.tf already computes.
resource "keycloak_group_admin_permissions" "institution_members" {
  for_each = local.institution_tiers            # (slug, tier) pairs

  realm_id = var.realm_id
  group_id = keycloak_group.institution[each.value.slug].id

  # capabilities from the template, filtered to the Groups resource type
  scopes   = each.value.group_scopes            # view-members / manage-members / manage-membership
  policies = [keycloak_role.grant[each.key].id] # the tier's grant role => role policy
}
```

Two rules carried over from the existing IaC: objects that already exist must be **imported**,
never recreated (`infra/terraform/scripts/import-existing.sh`); and `.terraform.lock.hcl` must
carry hashes for every platform that runs `terraform init`, or a Linux runner rejects a
Windows-generated lock.

---

## 8. What stays in the wrapper

FGAP v2 understands users, groups, clients and roles. It does **not** understand:

| Concern | Why FGAP v2 cannot express it |
|---|---|
| Per-client default-deny access policies (`sso-access-groups`, `ACCESS_DEFAULT_DENY`) | Our own login-time construct, not an admin-API operation |
| IPPIS-sourced attributes, joiner/mover/leaver, `employee_number` join key | Not an authorization concept at all |
| The `institution` claim ↔ grant role cross-check (`_assert_claim_corroborates`) | Detects drift between two of our own systems |
| The hash-chained audit service | A separate service with its own trust properties |
| **The guardrail** | FGAP v2 has no way to say "this person must *not* hold role X" |

The guardrail deserves the emphasis. A `realm-management` role still makes its holder a full
administrator and still bypasses every permission object, so the rule — no `realm-management`
role at all for a delegated admin, not even `view-users` — remains exactly as written today,
enforced at config load, at request time, and in the audit.

The wrapper therefore does not disappear. It becomes smaller and far less dangerous, because
it stops being the sole guard. Its remaining jobs are the four above, plus readable 403
messages, the audit events, `/institution-admin/permissions` previewing, and the admin-ui.

### 8.1 Do not hand local admins the raw Keycloak console

FGAP v2 makes a scoped console session technically possible. We should decline it, at least
initially: a local admin working directly in the console bypasses all four wrapper-only
concerns above. Keep the wrapper and admin-ui as the delegated surface, with FGAP v2 as the
enforcement floor beneath them.

---

## 9. Risks and open questions (the spike list)

> **Update 2026-08-19 — §9.1, §9.3 and §9.6 are answered, and §5/§7 need amending.**
> A Keycloak 26.2.5 spike ran the model end to end and Keycloak
> enforced all nine acceptance cases with no wrapper in the path:
> `docs/spikes/kc26-fgap-v2-orgs.md`. Three findings change the design as written
> here — subgroups do **not** propagate (§9.1); permissions covering the same
> object **veto** each other under the resource server's unchangeable UNANIMOUS
> strategy, so the projection must be per tier-*set*, not per tier; and role
> assignment needs an extra **realm-wide, shared** `Users/map-roles` permission
> that onboarding must update. FGAP v2 is **GA, not preview** (§9.3).
>
> **Update 2026-08-21 — this ADR is Accepted.** The platform standardises on
> Keycloak 26.2. §11 is rewritten accordingly.

### 9.1 Subgroup semantics — verify first

`members.include_subgroups: true` means `/minagri/planning` counts as MINAGRI. Whether one
FGAP v2 Groups permission reaches child groups automatically, or whether each child needs its
own resource entry, decides how many objects we generate and whether departmental staff are
visible. This project has already been bitten in exactly this area — the read path and the
write path disagreed about subgroups until `_member_group_ids` was made to walk the tree — so
this is the first thing to test, not the last.

### 9.2 Deny-by-omission

See §5.2. Mitigation: keep `_validate_template()` and extend `make delegated-check` to assert
that no permission grants a resource outside `inst:<slug>:*` / `<slug>-*` / `/<slug>`.

### 9.3 Preview status and support posture — ANSWERED

**FGAP v2 is GA, not preview,** on 26.2.5 as verified by the spike. The server feature is
`admin-fine-grained-authz:v2` — the versioned form of the old flag name; the server's feature
list shows the base name only, with versions appended as `:v2`. Enabling it is a supported
configuration, so the preview-risk concern that would have counted against this for a national
platform does not apply.

One adjacent trap found the same way: the Organizations feature is `organization`,
**singular** — the plural is rejected outright and the container restart-loops.

### 9.4 Object count and drift surface

Roughly 10–12 objects per institution across three tiers (one policy plus three or four
permissions per tier). At 50 institutions, several hundred authorization objects in one
resource server, all Terraform-managed. Plan for state size, plan evaluation time, and the cost
of the new drift audit.

### 9.5 Cross-realm admins

Master-realm administrators are governed by master's `<realm>-realm` roles, not by
`government-internal`'s permission objects. Confirm the exact behaviour in 26.x and keep the
master-realm account list minimal regardless.

### 9.6 Migration ordering

FGAP v1 configuration (which we do not use) is not automatically migrated to v2. Since we start
from no FGAP at all, this is a non-issue for us — recorded here only so nobody assumes otherwise.

---

## 10. Consequences

**Positive**

- The institution boundary is enforced by Keycloak, so it holds even when a caller bypasses the
  wrapper — the property today's design cannot provide at any level of test coverage.
- No ambient super-powered service-account token in the delegated path; the blast radius of a
  scoping bug shrinks from realm-wide to the permission graph.
- Keycloak's admin events name the real actor.
- The templates, the roles, the token surface, the registry and the onboarding procedure are
  all unchanged for the people who use them.
- ADR-0001's logical-isolation position gets materially stronger, which in turn makes the §6
  separate-realm exception harder to justify.

**Negative / to manage**

- Keycloak 26.2 operational changes (management port 9000, persistent sessions in PostgreSQL, JDK 21).
- Patterns become enumerations: correctness now depends on Terraform keeping permission lists in
  step with the roles and clients it creates.
- Denies become absences (§5.2).
- No hot reload: a policy change is a Keycloak write plus `tf apply`, where today it is a file
  edit plus `POST /delegated-permissions/reload`.
- A new failure mode to learn — "the permission object is missing" — alongside the familiar
  "the template does not allow it."
- The audit gets harder: diffing a policy graph, not just role holders.

**Neutral**

- Two enforcement layers for a transition period. Recommended: keep the wrapper's checks
  running alongside Keycloak's, so Keycloak refuses structurally while the wrapper still
  produces the readable message and the audit event. Any disagreement between the two is a bug
  worth finding, and `delegated-check` is the natural place to catch it.

---

## 11. Why this is now Accepted — which trigger fired

This ADR was first Proposed rather than Accepted, pending a reason to move the boundary into
Keycloak. Four triggers were named:

- an audit finding, or a real incident, that turns "the wrapper is the only guard" from a known
  design property into an accepted risk we can no longer accept;
- a requirement to let institutions administer themselves through any surface other than our
  wrapper;
- **standardising on Keycloak 26.x for an unrelated reason** (support window, CVE,
  Organizations, a needed 26.x feature) — in which case FGAP v2 comes nearly for free;
- institution count growing to the point where wrapper-side enforcement review does not scale.

**The third fired.** The platform standardises on Keycloak 26.2 for production, decided
independently of this ADR as part of production planning
(`docs/PROD_SERVER_REQUIREMENTS.md`, `docs/PROD_SIZING_RECONCILIATION.md` §2.4). The version
choice was therefore made for its own reasons, which is exactly the case in which this design was
always going to be worth taking — the enforcement move is nearly free once the version is
there, and the alternative is knowingly running 26.2 while leaving the institution boundary
in wrapper Python alone.

One thing acceptance does **not** mean:

- **It does not retire the wrapper's checks.** §10 "Neutral" stands: run both layers through
  the transition, with Keycloak refusing structurally while the wrapper produces the readable
  message and the audit event. Any disagreement between the two is a bug worth finding.

---

## 12. Action items

**Done** (as at acceptance, 2026-08-21):

- [x] Spike the subgroup question (§9.1) on a throwaway 26.x instance — **answered: subgroups
      do not propagate.** `docs/spikes/kc26-fgap-v2-orgs.md`.
- [x] Confirm the feature flag name and preview/GA status (§9.3) — **`admin-fine-grained-authz:v2`,
      GA.**
- [x] Confirm `python-keycloak` support for building an admin client from a caller-supplied
      bearer token (§6 Step 7) — `keycloak_admin_as_caller()` in
      `src/app/core/keycloak_clients.py`, behind `DELEGATED_EXECUTE_AS_CALLER`
      (now default `True`).
- [x] Confirm the FGAP v2 argument names on the `keycloak/keycloak` provider and write §7
      against the real schema — `infra/terraform/fgap_v2.tf` creates the role policy plus the
      group / role / client / users admin permissions; `scripts/render_fgap_plan.py` +
      `make fgap-plan` render the plan from `templates.json`.

**Remaining:**

- [x] Run the repository's stack on 26.2: `keycloak/Dockerfile` base image and the
      `docker-compose.kc26*.yml` files (dev, staging, prod) with `KC_PROXY_HEADERS`, hostname
      v2, health probes on the management port 9000 and `KC_BOOTSTRAP_ADMIN_*`.
- [ ] Deploy production on a greenfield 26.2 database.
- [ ] Create the realm-wide, shared `Users/map-roles` permission the spike found to be
      necessary for role assignment, and make onboarding update rather than replace it.
- [x] `DELEGATED_EXECUTE_AS_CALLER` defaults to `True` and Terraform's `enable_fgap_v2` to
      `true` — without the former FGAP v2 has no effect whatsoever (§6 Step 7).
- [ ] Extend `make delegated-check` with a permission-graph diff (§6 Step 10) — **unblocked by
      acceptance;** previously gated on it.
- [ ] Add scenario 5 of §6 Step 8 (the direct-Admin-API bypass test) to
      `docs/test-plans/delegated-permissions.md` as a standing acceptance criterion. Currently
      covered by `scripts/kc26_spike.py verify` (9/9) and
      `tests/e2e/test_keycloak_admin_as_caller.py`, but not yet written into the test plan.
- [ ] Decide how long both enforcement layers run in parallel (§10 "Neutral"), and what
      evidence retires the wrapper-side checks — if anything ever does.
