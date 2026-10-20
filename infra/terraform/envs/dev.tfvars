# Development environment.
#
# NO CREDENTIALS IN THIS FILE — it is committed. Connection details come from
# TF_VAR_keycloak_url / TF_VAR_keycloak_admin_username / TF_VAR_keycloak_admin_password.

environment = "dev"
realm       = "government-internal"

# Dev realms are built by Terraform, so it owns them outright.
manage_realm          = true
manage_platform_roles = true

# Dev-only conveniences. These wildcards must never appear in staging/prod
# tfvars, and must never be written into configs/institutions.yaml — the
# registry stays environment-independent.
extra_redirect_uris = [
  "http://localhost:*/*",
  "http://127.0.0.1:*/*",
]

extra_web_origins = ["+"]

# Keycloak is HTTP-only locally and on the staging box.
realm_ssl_required = "none"

enable_client_access_policies = true
