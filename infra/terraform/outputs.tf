output "realm" {
  description = "The shared institution realm this workspace manages."
  value       = var.realm
}

output "institutions" {
  description = "Every onboarded institution and what it owns."
  value = {
    for slug, m in module.institution : slug => {
      group_path       = m.group_path
      departments      = m.department_paths
      grant_roles      = m.grant_roles
      realm_roles      = m.realm_roles
      clients          = m.client_ids
      enforced_clients = m.enforced_client_ids
    }
  }
}

output "skipped_institutions" {
  description = <<-EOT
    Registry entries this workspace did NOT provision, and why: not active, in
    another realm, or a §6 separate-realm exception. Surfaced so a slug that
    quietly fell out of scope is visible in the apply output rather than only
    by its absence.
  EOT
  value = {
    for slug, i in local.all_institutions : slug => {
      status         = try(i.status, "active")
      realm          = try(i.realm, var.realm)
      separate_realm = try(i.separate_realm, false)
    }
    if !contains(keys(local.managed_institutions), slug)
  }
}

output "client_secrets" {
  description = "slug -> {client-id -> secret}. Sensitive: use `terraform output -json client_secrets`."
  value       = { for slug, m in module.institution : slug => m.client_secrets }
  sensitive   = true
}
