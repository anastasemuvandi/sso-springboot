# =============================================================================
# Onboard ONE institution into the shared `government-internal` realm.
#
# Everything ADR-0001 says an institution is, in one module:
#
#   §3  org/group          -> top-level group /<slug> (+ department subgroups),
#                             carrying the `institution` attribute
#   §4  admin role         -> the tiers' grant roles, `inst:<slug>:admin` first,
#                             rendered from the delegated-permission templates
#   §4  permissions        -> the same templates' `provision.roles`, so the only
#                             roles that exist are the ones the wrapper will
#                             agree to map
#       policies           -> the canonical `sso-access-groups` policy on each
#                             client, so it is usable under ACCESS_DEFAULT_DENY
#   §5.3 clients           -> `<slug>-<app>` + client roles + institution claim
#
# What it deliberately does NOT do: create users, or appoint anybody. Appointing
# a local admin is assigning them a grant role — a separate, individually
# audited act. See docs/runbooks/onboard-institution.md.
# =============================================================================

locals {
  # ---- roles: rendered from the delegated-permission templates --------------
  # Mirrors DelegatedPermissionTemplate.roles_to_provision(slug): the tier's
  # grant role plus its provision.roles, all inside inst:<slug>:*.
  tier_defs = { for name in var.tiers : name => var.templates[name] }

  grant_roles = {
    for name, t in local.tier_defs :
    replace(t.grant_role, "{slug}", var.slug) => name
  }

  provisioned_roles = distinct(flatten([
    for name, t in local.tier_defs : [
      for short in try(t.provision.roles, []) : "inst:${var.slug}:${short}"
    ]
  ]))

  # Grant-role descriptions win over provisioned ones: `inst:<slug>:admin` is
  # both a tier's grant role and the institution-admin tier's provisioned role,
  # and "this role IS the appointment" is the more useful thing to read in the
  # Keycloak console.
  realm_roles = merge(
    {
      for rn in local.provisioned_roles :
      rn => "${var.display_name} — ${trimprefix(rn, "inst:${var.slug}:")}"
    },
    {
      for rn, tier in local.grant_roles :
      rn => "${var.display_name} — ${try(var.templates[tier].display_name, tier)} (grant role: holding this IS the appointment)"
    },
  )

  # ---- clients --------------------------------------------------------------
  apps = { for a in var.apps : "${var.slug}-${a.name}" => a }

  # A public client has no resource server, so it cannot carry an access policy
  # and is therefore never enforced here.
  enforced = {
    for cid, a in local.apps :
    cid => var.enable_client_access_policies && a.enforce_access && !a.public
  }

  enforced_apps = { for cid, a in local.apps : cid => a if local.enforced[cid] }

  app_roles = { for r in flatten([
    for cid, a in local.apps : [
      for role in a.roles : { key = "${cid}/${role}", client = cid, role = role }
    ]
  ]) : r.key => r }
}

# -----------------------------------------------------------------------------
# §3 — the institution IS a top-level group
# -----------------------------------------------------------------------------

resource "keycloak_group" "institution" {
  realm_id = var.realm_id
  name     = var.slug

  attributes = {
    institution = var.slug
    displayName = var.display_name
  }

  lifecycle {
    # Destroying the group silently strips every member's institution
    # membership and their access to every client policy that admits it.
    prevent_destroy = true
  }
}

resource "keycloak_group" "department" {
  for_each = toset(var.departments)

  realm_id  = var.realm_id
  parent_id = keycloak_group.institution.id
  name      = each.value

  attributes = {
    institution = var.slug
  }
}

# -----------------------------------------------------------------------------
# §4 — the `inst:<slug>:*` namespace: grant roles and the roles they may map
# -----------------------------------------------------------------------------

resource "keycloak_role" "institution" {
  for_each = local.realm_roles

  realm_id    = var.realm_id
  name        = each.key
  description = each.value

  # No composite_roles, ever. A composite that reaches outside inst:<slug>:* is
  # precisely the broad grant the guardrail forbids, and it would be invisible
  # to a reader of the role name.
}

# Roles conferred by membership alone. Opt-in and normally empty — see the
# variable's description.
resource "keycloak_group_roles" "membership_defaults" {
  count = length(var.group_default_roles) > 0 ? 1 : 0

  realm_id = var.realm_id
  group_id = keycloak_group.institution.id

  role_ids = [
    for short in var.group_default_roles :
    keycloak_role.institution["inst:${var.slug}:${short}"].id
  ]

  # Additive: don't rip out a role an operator granted the group by hand for a
  # live incident. Drift is reported by `make delegated-check`, not silently
  # reverted at 3am by the next apply.
  exhaustive = false
}

# -----------------------------------------------------------------------------
# §5.3 — one client per app, slug-prefixed
# -----------------------------------------------------------------------------

resource "keycloak_openid_client" "app" {
  for_each = local.apps

  realm_id  = var.realm_id
  client_id = each.key
  name      = coalesce(each.value.display_name, "${var.display_name} — ${each.value.name}")
  enabled   = true

  access_type = each.value.public ? "PUBLIC" : "CONFIDENTIAL"

  standard_flow_enabled        = each.value.standard_flow
  implicit_flow_enabled        = false
  direct_access_grants_enabled = each.value.direct_access_grants

  # Authorization services require service accounts, so an enforced client gets
  # them whether or not the app asked for the client-credentials flow.
  service_accounts_enabled = each.value.public ? false : (each.value.service_accounts || local.enforced[each.key])

  valid_redirect_uris = distinct(concat(each.value.redirect_uris, var.extra_redirect_uris))
  web_origins         = distinct(concat(each.value.web_origins, var.extra_web_origins))
  root_url            = each.value.root_url

  # Client scope (spec §5.5): `sso.client.owner_institution` is what the wrapper's
  # ClientScope reads (the `<slug>-` prefix is only the fallback for older clients).
  # `institution` is kept for tooling that predates it (tools/seeder writes both).
  extra_config = {
    "sso.client.owner_institution" = var.slug
    institution                    = var.slug
  }

  dynamic "authorization" {
    for_each = local.enforced[each.key] ? [1] : []
    content {
      policy_enforcement_mode          = "ENFORCING"
      decision_strategy                = "UNANIMOUS"
      allow_remote_resource_management = true
    }
  }
}

resource "keycloak_role" "app" {
  for_each = local.app_roles

  realm_id    = var.realm_id
  client_id   = keycloak_openid_client.app[each.value.client].id
  name        = each.value.role
  description = "${var.display_name} — ${each.value.client} ${each.value.role}"
}

# The claim the whole scoping model reads: the wrapper's InstitutionContextResolver
# resolves a caller's institution from it, and the admin service uses it to
# inject the mandatory institution filter. Named exactly as the seed script
# names it so the two are interchangeable.
resource "keycloak_openid_user_attribute_protocol_mapper" "institution" {
  for_each = local.apps

  realm_id  = var.realm_id
  client_id = keycloak_openid_client.app[each.key].id
  name      = "institution-attribute"

  user_attribute   = "institution"
  claim_name       = "institution"
  claim_value_type = "String"

  add_to_id_token     = true
  add_to_access_token = true
  add_to_userinfo     = true
}

# -----------------------------------------------------------------------------
# Per-client access policy.
#
# Under ACCESS_DEFAULT_DENY a client with no policy rejects EVERY login at token
# time, so a newly onboarded institution's app would be dead on arrival without
# this. The policy name matches
# KeycloakAuthorizationService.GROUP_ACCESS_POLICY_NAME exactly, so a later
# change through /authorization updates this same policy instead of creating a
# rival one that Terraform then fights over.
# -----------------------------------------------------------------------------

resource "keycloak_openid_client_group_policy" "access" {
  for_each = local.enforced_apps

  realm_id           = var.realm_id
  resource_server_id = keycloak_openid_client.app[each.key].resource_server_id
  name               = "sso-access-groups"
  description        = "Managed by Terraform (infra/terraform): members of /${var.slug} may obtain tokens for ${each.key}."

  logic             = "POSITIVE"
  decision_strategy = "UNANIMOUS"

  groups {
    id              = keycloak_group.institution.id
    path            = keycloak_group.institution.path
    extend_children = var.access_extend_children
  }

  lifecycle {
    # Assignments made day-to-day through /authorization rewrite this policy's
    # membership. Terraform owns the policy's existence, not its exact member
    # list — reverting an operational grant on the next apply would be worse
    # than the drift.
    ignore_changes = [groups]
  }
}
