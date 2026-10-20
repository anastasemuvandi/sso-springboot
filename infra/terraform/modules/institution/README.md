# Module: `institution`

Onboards **one** institution into the shared `government-internal` realm.

Normally you do not call this module directly — the root module instantiates it
once per active entry in `configs/institutions.yaml`. Call it directly only when
you need an institution outside the registry (a throwaway test fixture, or a
§6 separate-realm exception in its own workspace).

## What it creates

| ADR-0001 | Resource |
|---|---|
| §3 org/group | `keycloak_group.institution` — `/<slug>`, carrying the `institution` attribute |
| §3 departments | `keycloak_group.department` — subgroups |
| §4 admin role | `keycloak_role.institution` — each tier's rendered `grant_role`, `inst:<slug>:admin` first |
| §4 permissions | the same tiers' `provision.roles`, so the only roles that exist are ones the wrapper will agree to map |
| §5.3 clients | `keycloak_openid_client.app` — `<slug>-<app>` + client roles |
| — claim | `keycloak_openid_user_attribute_protocol_mapper.institution` — the `institution` claim everything scopes on |
| — policies | `keycloak_openid_client_group_policy.access` — the canonical `sso-access-groups` policy |

It does **not** create users or appoint anybody. Assigning a grant role to a
person is a separate, individually audited act.

## Usage

```hcl
module "rura" {
  source = "./modules/institution"

  realm_id     = local.realm_id
  slug         = "rura"
  display_name = "Rwanda Utilities Regulatory Authority"

  templates = local.templates                  # templates.json -> .templates
  tiers     = ["institution-admin", "institution-hr"]

  departments = ["licensing", "inspection"]

  apps = [{
    name  = "licensing-portal"
    roles = ["viewer", "officer", "approver"]
  }]

  extra_redirect_uris = ["http://localhost:*/*"]   # dev only
}
```

## Inputs

| Name | Type | Default | Notes |
|---|---|---|---|
| `realm_id` | string | — | The shared institution realm. |
| `slug` | string | — | Canonical, permanent (ADR §5.1). Changing it **recreates everything** and invalidates live tokens. |
| `display_name` | string | — | The only field a rename may change. |
| `templates` | any | — | The `templates` object from `templates.json`, passed whole. |
| `tiers` | list(string) | — | Which template names apply here. |
| `departments` | list(string) | `[]` | Subgroups. Departments are never new slugs. |
| `apps` | list(object) | `[]` | See below. |
| `extra_redirect_uris` | list(string) | `[]` | Appended to every client — where environment-specific URLs go. |
| `extra_web_origins` | list(string) | `[]` | Appended to every client. |
| `enable_client_access_policies` | bool | `true` | Create `sso-access-groups`. Required under `ACCESS_DEFAULT_DENY`. |
| `access_extend_children` | bool | `false` | Matches what the wrapper writes. See below. |
| `group_default_roles` | list(string) | `[]` | Short role names granted by membership alone. Keep empty unless you mean it. |

### `apps` object

| Field | Default | Notes |
|---|---|---|
| `name` | — | Client-id becomes `<slug>-<name>`. Do not include the slug. |
| `display_name` | `<institution> — <name>` | |
| `roles` | `[]` | Client roles. |
| `redirect_uris` | `[]` | Environment-**independent** only. |
| `web_origins` | `[]` | `+` means "the valid redirect URIs". |
| `root_url` | `null` | |
| `public` | `false` | Public clients have no resource server, so they can carry no access policy and are never enforced. |
| `standard_flow` | `true` | |
| `direct_access_grants` | `false` | Password grant. Dev only. |
| `service_accounts` | `false` | Forced on for enforced clients — authorization services require it. |
| `enforce_access` | `true` | False leaves the client with no policy, so under `ACCESS_DEFAULT_DENY` it rejects every login until access is assigned via `/authorization`. |

## Outputs

`slug`, `group_id`, `group_path`, `department_paths`, `grant_roles`
(tier → the role that appoints it), `realm_roles`, `client_ids`,
`enforced_client_ids`, `client_secrets` (sensitive).

## Design notes

**Roles are rendered, not listed.** The module derives every `inst:<slug>:*`
role from the delegated-permission templates, mirroring
`PermissionTemplate.rolesToProvision()` (wrapper `policy.delegated`). A hard-coded list here would
be a second copy of the policy the wrapper actually enforces, free to drift from
it.

**No composite roles, ever.** A composite that reaches outside `inst:<slug>:*`
is the broad grant the guardrail forbids, and it would be invisible to anyone
reading the role name.

**The access policy is named `sso-access-groups`** because that is
`KeycloakAuthorizationService.GROUP_ACCESS_POLICY_NAME`. Matching it exactly
means a later change through `/authorization` updates *this* policy rather than
creating a rival one for Terraform to fight over. Its `groups` are under
`ignore_changes` for the same reason.

**`access_extend_children` defaults to false** to write byte-identical policies
to `set_client_assignments`, so an assignment made through the API and one made
by Terraform round-trip the same. It changes nothing for the wrapper's own
check, which compares the user's *direct* group memberships.

**`prevent_destroy` on the institution group.** Destroying it strips every
member's institution membership and their access to every client policy that
admits it. See the runbook's offboarding section for the supported retirement
path.
