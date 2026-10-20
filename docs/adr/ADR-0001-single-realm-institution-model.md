# ADR-0001 — One shared realm for all institutions (`government-internal`)

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-03 |
| **Author** | MUVANDIMWE Anastase |
| **Deciders** | GoR SSO / RISA identity team |
| **Relates to** | `docs/design.md` (3-realm tier model), `docs/implemented/keycloak-realms.md` |
| **Affects** | Keycloak topology, `src/app/api/agency.py`, IPPIS inbound mapping, per-client access enforcement, the admin service, onboarding runbooks |

---

## 1. Decision (the "one realm" step)

**We keep the three existing tier realms as-is, and we run a single shared realm for all
institutions: `government-internal`. We do _not_ create one realm per ministry/agency.**

- An **institution** (ministry, agency, board, authority) is **not** a realm. It is a
  first-class object *inside* `government-internal`, modeled as a **top-level Keycloak
  group** (§3).
- **Institution-level local admins** are delivered by the **custom admin service**
  (enforcement layer). On Keycloak 26.2 the same boundary is additionally enforced by
  Keycloak itself through FGAP v2 (`ADR-0002-fgap-v2-migration.md`) (§4).
- The three realms remain split by **security tier**, which is a separate axis from
  institution:

| Realm | Population | Role re: institutions |
|---|---|---|
| `government-internal` | Civil servants / staff | **The shared institution realm.** Every institution is a top-level group here; IPPIS feeds here; cross-ministry SSO and transfers happen here; local admins are scoped here. |
| `government-citizens` | Public — citizens, businesses | Institutions appear only as **owners of citizen-facing client apps**, never as groups a citizen belongs to. |
| `government-admin` | Platform / security admins | Institution largely N/A; at most a tag on a delegated admin. Isolation kept as-is. |

> **In one line:** *3 realms by security tier; institutions are groups inside
> `government-internal`, never realms of their own; local admins are scoped by the admin
> service, not by realm boundaries.*

---

## 2. Context

- **Engine:** Keycloak **26.2**. Delegation is delivered by the **custom admin service**
  (§4), and on 26.2 Keycloak's FGAP v2 enforces the same boundary inside Keycloak
  (`ADR-0002-fgap-v2-migration.md`, Accepted 2026-08-21). **The decision this ADR records is
  independent of that:** one shared realm, institutions as top-level groups. ADR-0002 §1.1
  and §4 keep it explicitly, and §4.1/§4.2 re-reject realm-per-institution on 26.
- **Institutions already exist as groups.** `src/app/api/agency.py` already documents and
  implements "each ministry/institution is a top-level group," with subgroups for
  departments and client roles for portal access. Seeded examples: `minagri`, `minisante`,
  `minijust`, `mineacom`.
- **IPPIS is the identity authority** for staff and emits an `institution` attribute per
  employee (e.g. `MINEDUC`, `MINISANTE`, `MINAGRI` — seeded in `ippis-mock`). Inbound sync
  writes `institution`/`position`/`ippis_status` onto the Keycloak user in
  `government-internal` (`docs/identity-source-integration-strategy.md`).
- **Access is already default-deny per client**, gated to assigned users/groups. Institution
  scoping rides on **group membership** and the **`institution` claim**, not on realm
  boundaries.
- **Growth expectation:** dozens of institutions and consuming apps over FY 2026/27
  (`docs/OKR_PLAN_2026.md`, KR2–KR4), each needing its own local admins.

### Options considered (institution axis only — the 3 tier realms are a settled premise)

| | **A. Institutions share `government-internal`** (chosen) | **B. Realm per institution** |
|---|---|---|
| **Scoped local admins** | **Possible** — admin service scopes an admin to one institution's group/roles/apps | **Impossible without over-privilege** — a per-realm admin is a *full realm admin*, the exact broad power the guardrail forbids |
| Cross-institution SSO | Native — one token/session across all staff apps | Broken — each realm is its own trust boundary; needs brokering |
| One person, many institutions (transfer, consultant) | One account, multiple group memberships | Duplicate accounts per realm; painful joiner/mover/leaver |
| IPPIS inbound sync | One target realm; `institution` is an attribute/group | Router must pick a realm per record; transfer = delete+recreate |
| Onboarding a new institution | Add a group + client(s); minutes | Provision + configure a whole realm; heavy |
| Operational surface (themes, flows, keys, upgrades) | One realm to patch and monitor | Multiplies with every institution — does not scale |
| Central reporting / audit across GoR | One realm, uniform queries | Must aggregate across N realms |
| Data isolation between institutions | Logical (group + access policy + admin-service filter) | Physical (realm) |

### Why A wins

A government-wide SSO exists so that a civil servant's single sign-on works across every
institution's apps they're entitled to, and so IPPIS lands each person in **one** account
regardless of posting. Realm-per-institution defeats both at the root, and — decisively for
this initiative — **it makes scoped local admins impossible**: the only admin you can grant
on a dedicated realm is a full realm admin, which is precisely the broad, fine-grained-check-
bypassing power the design forbids. A single shared realm is the *precondition* for delegated
institution admins. Option B's only real advantage (hard physical isolation) is delivered
instead by **per-client default-deny + group scoping + the admin service's mandatory
institution filter**, at a fraction of the operational cost.

### Consequences

- **Positive:** true cross-institution SSO; single IPPIS sync target; cheap institution
  onboarding; one realm to harden, back up, monitor, and upgrade; uniform audit/reporting;
  the strong `government-admin` isolation is untouched; **scoped local admins are achievable
  without a realm per institution**.
- **Negative / to manage:** isolation between institutions is *logical* — a misconfigured
  client, group mapper, or admin-service query could over-share. Mitigations: per-client
  default-deny (already in place), group-scoped role mappers, no wildcard client scopes, the
  admin service's mandatory institution filter (§4), and audit review of new clients.
- **Follow-on:** institution identity must be carried as an **attribute + group + token
  claim** so both downstream apps and the admin service can filter (§3, §4). The exceptions
  rule (§5) is the pressure valve for the rare institution that genuinely cannot share.

---

## 3. Institution model — *how* an institution is represented

**Rule of record:** **institution = one top-level Keycloak group in `government-internal`,
keyed by its canonical slug.**

- One **top-level group** per institution, e.g. `/minagri` (slug rules in §4).
- **Departments** = subgroups (`/minagri/planning`).
- **Portal access** = client roles on that group + per-client default-deny assignment.
- **Institution membership** is also mirrored onto the user as the **`institution`
  attribute** (from IPPIS) so it survives in tokens and downstream sync even where group
  structure isn't consumed. This attribute is what the admin service reads to scope a caller
  (§4).

> **Write-it-down summary:** *An institution is a top-level group (slug-keyed) in
> `government-internal`, carrying its members, department subgroups, and portal client-roles;
> each member also carries the `institution` attribute/claim.*

### Deferred / out of scope — Keycloak Organizations

Keycloak 26.2 ships the **Organizations** feature, but nothing here is scoped by
Organization. We therefore do **not** model institutions as Organizations now. Because the slug is the stable identifier for both a group
and (later) an Organization, a future migration would be additive and would **not** invalidate
this ADR — but it is explicitly **out of scope** and to be revisited only if the custom admin
service proves insufficient.

---

## 4. How this supports institution-level local admins

Local ("institution") admins are **delegated, scoped** admins who may manage only their own
institution's users, roles, and app access. This is delivered by the **custom admin
service** (task sheet "Admin service — enforcement layer"), and the institution
model above supplies every hook it needs:

| Local-admin building block | Supplied by |
|---|---|
| **Caller → institution resolution** | The **`institution` claim** on the admin's GoRSSO token (§3). The service extracts it and derives the caller's institution. |
| **Mandatory institution filter** on every operation | The service injects `institution = <caller's slug>` into every user/role/app query, so a list/search returns *only* that institution's users (closes the leakage gap). |
| **Role-namespacing `inst:<code>:<role>`** | The **slug is the `<code>`** (§5.1). `minagri` → `inst:minagri:admin`, `inst:minagri:hr`. Map-roles is scoped so an admin can only assign roles in their own `inst:<slug>:*` namespace. |
| **"Institution administrator" role** | A namespaced role (`inst:<slug>:admin`) held by the local admin; the service checks it before allowing scoped operations. |
| **Client-management scoping** | Per-institution app clients are slug-prefixed (`minagri-kubaka-web`, §5.3), so the service can list/allow only the caller institution's clients. |
| **Guardrail — never broad admin** | Local admins are **never** granted realm roles like `manage-users`/`realm-admin` (that bypasses the service's filter). All privilege flows through the scoped admin service using its own Keycloak service account. Enforced because everyone shares one realm — there is no per-realm admin to accidentally hand out. |

This is exactly why **single realm is a precondition** (§2): the scoping above is only
expressible when all institutions live in one realm and admins are bounded by
group/slug/claim rather than by realm ownership.

---

## 5. Naming & codes (slugs) — the canonical identifier

Every institution has exactly **one canonical slug**, identical everywhere: group name,
`institution` claim, `inst:<code>:<role>` namespace, client-id prefix, config keys, docs, and
onboarding tickets. This removes the current split between lowercase code slugs (`minagri`)
and IPPIS's uppercase values (`MINEDUC`).

### 5.1 Slug rules

1. **Canonical form is lowercase** — `minagri`, `minisante`, `minijust`, `mineacom`.
   (Matches existing code and demo apps; least churn.)
2. Characters: `[a-z0-9-]` only. Words joined by `-` when needed (`local-govt`). No spaces,
   dots, or underscores (underscores would collide with the `inst:<code>:<role>` reader).
3. **Stable and permanent.** A slug never changes once issued — even on rename or merger —
   because it is embedded in tokens, group paths, role namespaces, and client-ids. Renames
   update the *display name*, never the slug.
4. Prefer the institution's **established acronym** (MINAGRI, RDB, RRA, RURA). Invent a slug
   only when no standard acronym exists.
5. One slug per legal institution. Departments are **subgroups**, not new slugs.

### 5.2 IPPIS reconciliation (uppercase → slug)

IPPIS emits `institution` in uppercase (`MINEDUC`). The inbound mapper
(`src/app/services/ippis_*.py`) must **normalize to the canonical lowercase slug** on ingest
via an explicit lookup table — never a naive `.lower()`, because IPPIS org codes and our slugs
won't always be a trivial case-fold (an IPPIS org unit → institution slug may be many-to-one).
Keep the mapping in one place (the registry — §5.4).

### 5.3 Where the slug appears (must be identical in all)

| Surface | Example |
|---|---|
| Keycloak group path (`government-internal`) | `/minagri` |
| Token claim (`institution`) | `"institution": "minagri"` |
| Role namespace | `inst:minagri:admin` |
| Client-id prefix (per-institution apps) | `minagri-kubaka-web` |
| Config / env keys | `MINAGRI_CLIENT_SECRET` |
| Onboarding ticket / docs anchor | `minagri` |

### 5.4 Institution registry (source of truth)

Maintain a single **institution registry** — `configs/institutions.yaml` (or a DB table) — as
the authoritative list. Minimum fields:

```yaml
- slug: minagri            # canonical, permanent, lowercase; == inst:<code>
  display_name: Ministry of Agriculture and Animal Resources
  ippis_codes: [MINAGRI]   # all upstream IPPIS values that map to this slug
  status: active           # active | suspended | decommissioned
  realm: government-internal
  separate_realm: false    # true only for §6 legal exceptions
```

The seed/onboarding scripts, the IPPIS normalizer, the admin service, and any "list
institutions" API should read from this registry rather than hard-coding names (today
`dashboard.py` hard-codes `agencies_count = 4` — fold that into the registry).

---

## 6. Exceptions rule — when an institution _may_ get its own realm

Default is **no exceptions**: every institution lives in `government-internal`. A separate
realm is granted **only** when *all* of the following hold, and only with a signed exception
record:

1. **Legal / regulatory mandate** for physical identity isolation that logical controls
   provably cannot satisfy — e.g. a statute or classification regime requiring the
   institution's authentication data to be stored and administered separately (candidate
   domains: defence, intelligence / national-security, or a body with its own binding
   data-protection instrument).
2. **Independent administration** is legally required — the institution's own security
   authority must control users, flows, and keys, with no shared platform admin.
3. **Logical controls are insufficient** — per-client default-deny, group scoping, and the
   admin service's mandatory institution filter have been evaluated and formally found
   inadequate for *this specific* legal requirement. Brokering the institution's own IdP into
   `government-internal` is the **first** answer to "we want independence" and must be ruled
   out before a realm is granted.

**Process for an exception:**

- Raise an exception record referencing the specific law/regulation.
- Approval by the SSO governance owner **and** RISA security.
- Record it in the registry: `separate_realm: true`, `realm: <its-realm>`, plus a
  `justification:` citing the mandate.
- Accept the cost: that institution loses native cross-institution SSO **and** delegated local
  admins, and must be **brokered** back for any shared apps.

**Explicitly _not_ grounds for a separate realm:** custom login branding (use a theme), wanting
your own IdP (use IdP brokering into `government-internal`), org-chart pride, "we've always had
our own system," or generic data-sensitivity without a specific legal isolation mandate.

Until a real, legally-backed case appears, the exception list is **empty**.

---

## 7. Action items (from this decision)

- [x] Create `configs/institutions.yaml` registry (§5.4); migrate the seeded ministries and
      `dashboard.py`'s hard-coded count into it.
      *Registry created and adopted as the input to the onboarding IaC
      (`infra/terraform`). Folding `dashboard.py`'s hard-coded `agencies_count = 4`
      into it is still outstanding.*
- [ ] Add an IPPIS uppercase→slug normalization table to the inbound mapper (§5.2).
- [ ] Confirm the lowercase-slug convention across code, demos, and docs; flag any uppercase
      `institution` values written to Keycloak for normalization.
- [ ] Adopt the `inst:<slug>:<role>` namespacing scheme and the `inst:<slug>:admin` role (§4);
      wire the admin service's mandatory institution filter to the `institution` claim.
- [x] Add "institution onboarding = new registry entry + top-level group in
      `government-internal` + `inst:<slug>:admin` role + client(s)" to the onboarding runbook.
      *`docs/runbooks/onboard-institution.md`. Onboarding is now a registry entry plus
      an apply — `infra/terraform` creates the group, roles, clients and access policy,
      and CI applies it to dev.*
- [ ] Keep Keycloak **Organizations out of scope** (§3); revisit only when there is a reason
      to scope something by Organization.
- [ ] Keep the exception list (§6) in the registry; review any request against the three
      conditions.
