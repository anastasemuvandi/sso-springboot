output "slug" {
  description = "The institution's canonical slug."
  value       = var.slug
}

output "group_id" {
  description = "Keycloak id of the top-level institution group."
  value       = keycloak_group.institution.id
}

output "group_path" {
  description = "Group path, e.g. /minagri."
  value       = keycloak_group.institution.path
}

output "department_paths" {
  description = "Paths of the department subgroups."
  value       = { for name, g in keycloak_group.department : name => g.path }
}

output "grant_roles" {
  description = "tier name -> the realm role that appoints it. Assigning one of these IS the appointment."
  value       = { for role, tier in local.grant_roles : tier => role }
}

output "realm_roles" {
  description = "Every inst:<slug>:* realm role this institution owns."
  value       = sort(keys(local.realm_roles))
}

output "client_ids" {
  description = "The institution's client-ids."
  value       = sort(keys(local.apps))
}

output "enforced_client_ids" {
  description = "Clients carrying an sso-access-groups policy. Anything else is subject to ACCESS_DEFAULT_DENY."
  value       = sort(keys(local.enforced_apps))
}

output "client_secrets" {
  description = <<-EOT
    client-id -> generated secret, for handing to the integrating team. Public
    clients report null. Treat as a credential: it is in the state file, and
    reading it here is the only sanctioned way to retrieve it.
  EOT
  value       = { for cid, c in keycloak_openid_client.app : cid => c.client_secret }
  sensitive   = true
}

# ---------------------------------------------------------------------------
# Id lookups for the FGAP v2 admin permissions (ADR-0002 §7).
#
# `configs/generated/fgap-v2-plan.json` is written in the vocabulary of the
# config files — group paths, role names, client-ids — because that is what a
# reviewer reads. Keycloak's admin-permission resources want object ids. These
# outputs are the translation, and they live here because this module is what
# creates the objects in the first place.
# ---------------------------------------------------------------------------

output "group_ids_by_path" {
  description = "Group path -> Keycloak id, for the institution group and every department."
  value = merge(
    { (keycloak_group.institution.path) = keycloak_group.institution.id },
    { for name, g in keycloak_group.department : g.path => g.id },
  )
}

output "realm_role_ids" {
  description = "inst:<slug>:* realm-role name -> Keycloak id."
  value       = { for name, r in keycloak_role.institution : name => r.id }
}

output "client_uuids" {
  description = "client-id (<slug>-<app>) -> Keycloak's internal client uuid."
  value       = { for client_id, c in keycloak_openid_client.app : client_id => c.id }
}
