# Staging environment — the 10.10.73.8 box.
#
# NO CREDENTIALS IN THIS FILE. See envs/dev.tfvars.
#
# The staging realm predates this IaC, so `manage_realm = false`: Terraform
# reads the realm and manages the institutions inside it, without asserting
# ownership of realm-wide settings it never created. Flip to true only after
# running infra/terraform/scripts/import-existing.sh and reviewing the plan —
# a plan that proposes to CHANGE realm settings, rather than only adopt them,
# means the live realm has drifted from this code and the drift must be
# resolved before applying.

environment = "staging"
realm       = "government-internal"

manage_realm          = false
manage_platform_roles = false

# Replace with the real staging app hosts as they are integrated. No wildcards:
# a wildcard redirect URI on a confidential client is an open redirect.
extra_redirect_uris = []
extra_web_origins   = []

realm_ssl_required = "none" # HTTP-only today; move to "external" at the TLS cutover

enable_client_access_policies = true
