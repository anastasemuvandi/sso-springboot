# =============================================================================
# One module instance per active institution in the registry.
#
# Onboarding an institution is therefore a registry edit and an apply. There is
# no per-institution Terraform to write, and nothing to click.
# =============================================================================

module "institution" {
  source   = "./modules/institution"
  for_each = local.managed_institutions

  realm_id     = local.realm_id
  slug         = each.key
  display_name = each.value.display_name

  templates = local.templates
  tiers     = local.tiers_for[each.key]

  departments = try(each.value.departments, [])
  apps        = try(each.value.apps, [])

  extra_redirect_uris = var.extra_redirect_uris
  extra_web_origins   = var.extra_web_origins

  enable_client_access_policies = var.enable_client_access_policies
  access_extend_children        = var.access_extend_children

  group_default_roles = try(each.value.group_default_roles, [])

  depends_on = [
    terraform_data.registry_guard,
    keycloak_role.platform,
  ]
}
