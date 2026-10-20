# =============================================================================
# FGAP v2 admin permissions — ADR-0002.
#
# What this does: makes KEYCLOAK enforce the delegated-admin boundary, instead
# of the boundary living only in the wrapper. Every object here is a
# projection of `configs/delegated-permissions/templates.json` — the same file
# the wrapper reads at request time — so the two cannot describe different
# rules.
#
# REQUIRES KEYCLOAK 26.2+ with `admin-fine-grained-authz:v2` (keycloak/Dockerfile
# builds it in). `enable_fgap_v2` defaults to true; the wrapper's
# DELEGATED_EXECUTE_AS_CALLER (also on by default) is what makes Keycloak
# consult these permissions.
#
# ---------------------------------------------------------------------------
# Why the plan is generated rather than computed here
# ---------------------------------------------------------------------------
# The projection is not a mapping; it is a grouping. A permission must cover the
# set of objects granted by exactly the same SET of tiers, because the
# admin-permissions resource server combines permissions with the UNANIMOUS
# strategy and that setting cannot be changed on it: two permissions over one
# object make the tiers veto each other, and a local admin gets 403 on their own
# institution with nothing pointing at the cause
# (docs/spikes/kc26-fgap-v2-orgs.md §3.1).
#
# That algorithm lives in the wrapper's pure `policy.fgap` module
# (FgapProjection / FgapPlanRenderer), where it is unit-tested. Written
# a second time in HCL it would be unreadable and free to drift from what the
# wrapper enforces — the one thing this design exists to prevent. So it is
# rendered to `configs/generated/fgap-v2-plan.json` and read here as data, the
# same generate-commit-gate pattern the repo already uses for `openapi.json`
# (`make fgap-plan`, `make fgap-plan-check`, i.e.
# `java -jar sso-wrapper/target/sso-wrapper-*.jar fgap-plan[-check]`). Terraform
# never runs a generator itself: no `external` data source, no `local-exec`.
# =============================================================================

locals {
  fgap_enabled = var.enable_fgap_v2

  fgap_plan = jsondecode(file(var.fgap_plan_file))

  # Name -> object id, gathered from every institution module. The plan speaks
  # in paths/role names/client-ids; Keycloak wants ids.
  fgap_group_ids = merge([
    for slug, m in module.institution : m.group_ids_by_path
  ]...)
  fgap_role_ids = merge([
    for slug, m in module.institution : m.realm_role_ids
  ]...)
  fgap_client_uuids = merge([
    for slug, m in module.institution : m.client_uuids
  ]...)

  fgap_policies = local.fgap_enabled ? {
    for p in local.fgap_plan.policies : p.name => p
  } : {}

  fgap_permissions_by_type = {
    for type in ["Groups", "Roles", "Clients", "Users"] : type => {
      for p in local.fgap_plan.permissions : p.name => p
      if local.fgap_enabled && p.resource_type == type
    }
  }

  # The plan and the registry must describe the same set of institutions. Either
  # direction being non-empty means the committed plan was rendered from a
  # different registry than the one being applied.
  fgap_planned_institutions = toset([
    for i in local.fgap_plan.institutions : i if i != "_shared"
  ])
  fgap_unknown_institutions = setsubtract(
    local.fgap_planned_institutions, toset(keys(local.managed_institutions))
  )
  fgap_missing_institutions = setsubtract(
    toset(keys(local.managed_institutions)), local.fgap_planned_institutions
  )
}

# ---------------------------------------------------------------------------
# Preconditions
#
# A stale plan is the failure mode this file must not have: it applies cleanly,
# the audit reports nothing, and an institution is quietly under-permissioned.
# Fail the plan instead.
# ---------------------------------------------------------------------------

resource "terraform_data" "fgap_guard" {
  count = local.fgap_enabled ? 1 : 0

  input = {
    policies    = length(local.fgap_plan.policies)
    permissions = length(local.fgap_plan.permissions)
  }

  lifecycle {
    precondition {
      condition     = try(local.fgap_plan.version, 0) == 1
      error_message = "${var.fgap_plan_file} is not a version 1 plan. Regenerate it: make fgap-plan"
    }

    # Every institution the plan mentions must be one this root module manages,
    # and vice versa.
    precondition {
      condition     = length(local.fgap_unknown_institutions) == 0
      error_message = "${var.fgap_plan_file} covers institutions this configuration does not manage: ${jsonencode(local.fgap_unknown_institutions)}. The plan is stale or came from a different registry. Run: make fgap-plan"
    }

    # The dangerous direction: a missing institution applies cleanly and leaves
    # its local admins with no Keycloak-side permissions at all.
    precondition {
      condition     = length(local.fgap_missing_institutions) == 0
      error_message = "These managed institutions are missing from ${var.fgap_plan_file}: ${jsonencode(local.fgap_missing_institutions)}. Their local admins would have NO Keycloak-side permissions. Run: make fgap-plan"
    }
  }
}

# ---------------------------------------------------------------------------
# The resource server
#
# Enabling admin permissions on the realm is what materialises the
# `admin-permissions` client; see base.tf, where the realm carries the flag.
# The permission resources below discover the resource server themselves
# (`authorization_resource_server_id` is computed), but a role policy needs it
# explicitly.
# ---------------------------------------------------------------------------

data "keycloak_openid_client" "admin_permissions" {
  count = local.fgap_enabled ? 1 : 0

  realm_id  = local.realm_id
  client_id = "admin-permissions"
}

# ---------------------------------------------------------------------------
# Policies — the "who"
#
# One role policy per SET of tiers that share a grant. `required = false` is
# load-bearing: Keycloak reads a role policy with several non-required roles as
# "any one of these", which is exactly `merge_grants()`'s "allows add up".
# `required = true` would demand the caller hold every tier at once.
# ---------------------------------------------------------------------------

resource "keycloak_openid_client_role_policy" "delegated" {
  for_each = local.fgap_policies

  realm_id           = local.realm_id
  resource_server_id = data.keycloak_openid_client.admin_permissions[0].id
  name               = each.key
  # Keycloak stores this in a varchar(255) and answers a bare 500 when it
  # overruns (spike §4), so the generated description is trimmed here.
  description       = substr(each.value.description, 0, 250)
  type              = "role"
  logic             = "POSITIVE"
  decision_strategy = "UNANIMOUS"

  dynamic "role" {
    for_each = each.value.roles
    content {
      id       = local.fgap_role_ids[role.value]
      required = false
    }
  }

  depends_on = [terraform_data.fgap_guard]
}

# ---------------------------------------------------------------------------
# Permissions — the "what, on which things"
#
# One resource type per block. They differ only in which id list they take, and
# in that `Users` takes none at all: see the note on the users permission below.
# ---------------------------------------------------------------------------

resource "keycloak_group_admin_permissions" "delegated" {
  for_each = local.fgap_permissions_by_type["Groups"]

  realm_id          = local.realm_id
  name              = each.key
  description       = substr(each.value.description, 0, 250)
  scopes            = each.value.scopes
  group_ids         = [for path in each.value.resources : local.fgap_group_ids[path]]
  policies          = [for name in each.value.policies : keycloak_openid_client_role_policy.delegated[name].id]
  decision_strategy = "UNANIMOUS"
}

resource "keycloak_role_admin_permissions" "delegated" {
  for_each = local.fgap_permissions_by_type["Roles"]

  realm_id          = local.realm_id
  name              = each.key
  description       = substr(each.value.description, 0, 250)
  scopes            = each.value.scopes
  role_ids          = [for role in each.value.resources : local.fgap_role_ids[role]]
  policies          = [for name in each.value.policies : keycloak_openid_client_role_policy.delegated[name].id]
  decision_strategy = "UNANIMOUS"
}

resource "keycloak_openid_client_admin_permissions" "delegated" {
  for_each = local.fgap_permissions_by_type["Clients"]

  realm_id          = local.realm_id
  name              = each.key
  description       = substr(each.value.description, 0, 250)
  scopes            = each.value.scopes
  client_ids        = [for client_id in each.value.resources : local.fgap_client_uuids[client_id]]
  policies          = [for name in each.value.policies : keycloak_openid_client_role_policy.delegated[name].id]
  decision_strategy = "UNANIMOUS"
}

# The role-mapping ACTION gate. Deliberately realm-wide, and deliberately a
# single shared object:
#
#   * `Roles/map-role` on a role and `Groups/manage-members` on the group are not
#     enough to assign that role to a member — Keycloak also requires
#     `Users/map-roles`, which gates the action while the others gate the target.
#   * A per-institution copy would cover every user, so under UNANIMOUS the
#     copies would veto each other and role assignment would fail for everyone.
#
# Verified safe: a minagri admin mapping their own role onto a MINISANTE user is
# still refused, because the target check comes from group membership
# (docs/spikes/kc26-fgap-v2-orgs.md §3.2). Note the resource takes no id list at
# all — the provider has no way to scope it, which is the same conclusion the
# spike reached empirically.
resource "keycloak_users_admin_permissions" "delegated" {
  for_each = local.fgap_permissions_by_type["Users"]

  realm_id          = local.realm_id
  name              = each.key
  description       = substr(each.value.description, 0, 250)
  scopes            = each.value.scopes
  policies          = [for name in each.value.policies : keycloak_openid_client_role_policy.delegated[name].id]
  decision_strategy = "UNANIMOUS"
}
