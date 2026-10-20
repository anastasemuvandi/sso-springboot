provider "keycloak" {
  # Every setting is passed EXPLICITLY rather than left to the provider's env
  # var fallbacks. This is not stylistic: the repo's own `.env` defines
  # KEYCLOAK_CLIENT_ID=sso-client and KEYCLOAK_REALM=government-internal for the
  # Spring Boot wrapper, and the provider reads those exact variable names. A shell
  # that has sourced `.env` would otherwise silently make Terraform authenticate
  # as the wrapper's service account against the wrong realm. Binding each
  # argument to a TF variable makes ambient KEYCLOAK_* values inert.
  url       = var.keycloak_url
  base_path = var.keycloak_base_path

  # Admin credentials live in the `master` realm (same as the wrapper's admin
  # client — see the wrapper's KeycloakAdminFactory), using the built-in admin-cli
  # public client with the password grant.
  realm     = var.keycloak_admin_realm
  client_id = var.keycloak_admin_client_id
  username  = var.keycloak_admin_username
  password  = var.keycloak_admin_password

  # Don't authenticate while the provider is being configured. Without this a
  # `terraform plan` from a machine that cannot reach Keycloak fails during
  # provider setup instead of at the first resource, which makes the offline
  # `validate` CI job impossible.
  initial_login = false

  tls_insecure_skip_verify = var.keycloak_tls_insecure_skip_verify
}
