# -----------------------------------------------------------------------------
# Connection
#
# Credentials are NEVER put in an envs/*.tfvars file (those are committed). Pass
# them as TF_VAR_* environment variables, from CI secrets or a local shell.
# -----------------------------------------------------------------------------

variable "keycloak_url" {
  description = "Keycloak base URL, no trailing slash (e.g. http://10.10.73.8:8080)."
  type        = string
}

variable "keycloak_base_path" {
  description = "Path prefix. Empty for the Quarkus distribution; '/auth' only for legacy Wildfly."
  type        = string
  default     = ""
}

variable "keycloak_admin_realm" {
  description = "Realm the admin account lives in. Always 'master' for our deployment."
  type        = string
  default     = "master"
}

variable "keycloak_admin_client_id" {
  description = "Client used for the admin password grant."
  type        = string
  default     = "admin-cli"
}

variable "keycloak_admin_username" {
  description = "Keycloak master-realm admin username. Supply via TF_VAR_keycloak_admin_username."
  type        = string
}

variable "keycloak_admin_password" {
  description = "Keycloak master-realm admin password. Supply via TF_VAR_keycloak_admin_password."
  type        = string
  sensitive   = true
}

variable "keycloak_tls_insecure_skip_verify" {
  description = "Skip TLS verification. The staging target is plain HTTP today, so this stays false."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Environment
# -----------------------------------------------------------------------------

variable "environment" {
  description = "Environment this workspace targets. Guards dev-only conveniences."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "realm" {
  description = <<-EOT
    The shared institution realm (ADR-0001). Institutions are groups inside it;
    there is deliberately no realm-per-institution.
  EOT
  type        = string
  default     = "government-internal"
}

# -----------------------------------------------------------------------------
# Inputs — the two files that drive everything
# -----------------------------------------------------------------------------

variable "institutions_file" {
  description = "Institution registry (ADR-0001 §5.4). WHICH institutions exist."
  type        = string
  default     = "../../configs/institutions.yaml"
}

variable "delegated_templates_file" {
  description = <<-EOT
    Delegated-permission templates (ADR-0001 §4). WHAT each local-admin tier may
    do. Read here so the `inst:<slug>:*` realm roles Terraform creates are
    derived from the same policy the wrapper enforces at request time, instead
    of being a second, drifting copy of it.
  EOT
  type        = string
  default     = "../../configs/delegated-permissions/templates.json"
}

# -----------------------------------------------------------------------------
# Base setup toggles
# -----------------------------------------------------------------------------

variable "manage_realm" {
  description = <<-EOT
    Let Terraform own the realm's own settings (themes, token lifespans, brute
    force, password policy, events). True for an environment Terraform built.
    Set false to attach to a realm whose lifecycle is owned elsewhere — the
    institutions are then still managed, the realm settings are only read.

    Flipping this true -> false on a workspace that already manages the realm
    asks Terraform to DESTROY it, taking every user and session with it.
    `prevent_destroy` blocks that, deliberately. The safe way to hand the realm
    back is `terraform state rm keycloak_realm.internal[0]`, which forgets it
    without touching it.
  EOT
  type        = bool
  default     = true
}

variable "manage_platform_roles" {
  description = "Manage the realm-wide platform roles (super_admin, admin, ...). Independent of manage_realm."
  type        = bool
  default     = true
}

variable "platform_realm_roles" {
  description = "Realm-wide roles the wrapper's RBAC reads (the admin-tier gate in RouteRules). Mirrors configs/realms/government-internal-realm.json."
  type        = map(string)
  default = {
    super_admin = "Highest-tier wrapper RBAC role (passes every platform route)"
    admin       = "Administrator with full access"
    employee    = "Basic employee role"
    contractor  = "Contractor role"
    gov-admin   = "Government administrator"
    auditor     = "Auditor across systems"
    citizen     = "Citizen accessing public services"
  }
}

variable "default_realm_roles" {
  description = "Roles every new user receives (the default-roles-<realm> composite)."
  type        = list(string)
  default     = ["offline_access", "uma_authorization", "employee"]
}

# -----------------------------------------------------------------------------
# Realm settings (only applied when manage_realm = true)
# -----------------------------------------------------------------------------

variable "realm_display_name" {
  type    = string
  default = "Government Internal - Staff Portal"
}

variable "realm_organizations_enabled" {
  description = "Keycloak Organizations on the realm. Nothing is scoped by Organization (ADR-0001), so off unless there is a reason."
  type        = bool
  default     = false
}

# Account/admin/email themes: null leaves Keycloak's own defaults. There is no
# `keycloak` theme in these categories on 26.2, and the provider refuses a theme
# the server lacks ("theme \"keycloak\" does not exist on the server").
variable "realm_account_theme" {
  type    = string
  default = null
}

variable "realm_admin_theme" {
  type    = string
  default = null
}

variable "realm_email_theme" {
  type    = string
  default = null
}

variable "realm_login_theme" {
  type    = string
  default = "government-citizens"
}

variable "realm_ssl_required" {
  description = "'none' while the deployment is HTTP-only; move to 'external' the moment TLS lands."
  type        = string
  default     = "none"

  validation {
    condition     = contains(["none", "external", "all"], var.realm_ssl_required)
    error_message = "realm_ssl_required must be one of: none, external, all."
  }
}

variable "realm_password_policy" {
  type    = string
  default = "length(10) and upperCase(1) and lowerCase(1) and digits(1)"
}

variable "realm_access_token_lifespan" {
  type    = string
  default = "15m"
}

variable "realm_sso_session_idle_timeout" {
  type    = string
  default = "15m"
}

variable "realm_sso_session_max_lifespan" {
  type    = string
  default = "8h"
}

variable "realm_login_failure_factor" {
  description = "Consecutive failures before a temporary lockout. OTP and recovery-code failures count too: Keycloak's brute-force detector sees every INVALID_CREDENTIALS from the browser flow, whichever form raised it."
  type        = number
  default     = 5
}

# -----------------------------------------------------------------------------
# Per-environment client settings
# -----------------------------------------------------------------------------

variable "extra_redirect_uris" {
  description = <<-EOT
    Redirect URIs appended to EVERY institution client, on top of whatever the
    registry declares. This is where environment-specific URLs belong — the
    registry must stay environment-independent, otherwise a dev localhost URL
    ends up whitelisted in production.
  EOT
  type        = list(string)
  default     = []
}

variable "extra_web_origins" {
  description = "Web origins appended to every institution client. '+' means 'the valid redirect URIs'."
  type        = list(string)
  default     = []
}

variable "enable_client_access_policies" {
  description = <<-EOT
    Create the canonical `sso-access-groups` policy on each institution client,
    admitting that institution's group. Required under ACCESS_DEFAULT_DENY —
    without it a freshly created client rejects every login. Set false only to
    debug the enforcement layer itself.
  EOT
  type        = bool
  default     = true
}

variable "access_extend_children" {
  description = <<-EOT
    Whether the access policy extends to department subgroups. Defaults to false
    to write byte-identical policies to
    KeycloakAuthorizationService.set_client_assignments, so an assignment made
    through /authorization and one made by Terraform round-trip the same.
    Note this changes nothing for the wrapper's own check, which compares the
    user's DIRECT group memberships.
  EOT
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# FGAP v2 (ADR-0002)
# ---------------------------------------------------------------------------

variable "enable_fgap_v2" {
  description = <<-EOT
    Create the Keycloak admin permissions that let KEYCLOAK enforce the
    delegated-admin boundary, instead of it living only in the wrapper.

    REQUIRES KEYCLOAK 26.2+ with FGAP v2. Pairs with the wrapper's
    DELEGATED_EXECUTE_AS_CALLER (on by default): without that, calls arrive as
    the service account and these permissions are never consulted.
  EOT
  type        = bool
  default     = true
}

variable "fgap_plan_file" {
  description = <<-EOT
    The generated projection of the delegated-permission templates —
    `make fgap-plan` writes it, CI fails if it is stale. Read as data rather
    than recomputed in HCL so the grouping algorithm (which is subtle: see
    fgap_v2.tf) exists once, in the wrapper's tested Java (policy.fgap).
  EOT
  type        = string
  default     = "../../configs/generated/fgap-v2-plan.json"
}

# ---------------------------------------------------------------------------
# Realm-wide TOTP (mfa.tf, docs/implemented/mfa-totp.md)
# ---------------------------------------------------------------------------

variable "enable_mfa_totp" {
  description = <<-EOT
    Create the `browser-totp` flow, bind it as the browser flow, and create the
    `mfa-exempt` role and the TOTP / recovery-code required actions.

    REQUIRES KEYCLOAK 26.2+ with the gorsso-mfa provider JAR in
    /opt/keycloak/providers and `recovery-codes` in KC_FEATURES (preview in
    26.2.5). Without them the apply fails on the first execution naming a
    missing provider — hence false, like enable_fgap_v2.

    This is NOT the switch. The switch is the realm attribute
    `mfa_totp_enforced`, owned by the super admin through the wrapper once the
    flow exists.
  EOT
  type        = bool
  default     = false
}

variable "mfa_totp_mode_initial" {
  description = <<-EOT
    Value written to the realm attribute `mfa_totp_mode` when Terraform creates
    the realm: "off", "per_client" (a client requires TOTP unless its
    sso.client.mfa_required is "false") or "all". Ignored afterwards
    (ignore_changes in base.tf): the super admin changes it at runtime, and a
    later apply must not change it back. "off" is the safe choice for a realm
    whose staff have not been told yet.
  EOT
  type        = string
  default     = "all"

  validation {
    condition     = contains(["off", "per_client", "all"], var.mfa_totp_mode_initial)
    error_message = "mfa_totp_mode_initial must be off, per_client or all."
  }
}

variable "mfa_default_actions_for_new_users" {
  description = <<-EOT
    Make CONFIGURE_TOTP and CONFIGURE_RECOVERY_AUTHN_CODES default required
    actions, so every NEW user enrols at first login. Off by default: it is
    independent of the switch, so with it on a user created while TOTP is off
    (or who only uses apps that need no code) is still made to enrol — and the
    browser-totp flow's `enrol` branch already enrols anyone, at their first
    login to an app that does require a code.
  EOT
  type        = bool
  default     = false
}

variable "mfa_loa_max_age_seconds" {
  description = "How long a level of authentication (1 = password, 2 = TOTP) stays valid for SSO-cookie logins, so acr stays 2 within a session. Default = ssoSessionMaxLifespan's default, 10h."
  type        = number
  default     = 36000
}

variable "enable_organization_login" {
  description = "Keep the built-in 26.2 browser flow's Organization identity-first sub-flow in browser-totp. Needs the `organization` feature and organizationsEnabled on the realm."
  type        = bool
  default     = true
}

variable "realm_otp_policy" {
  description = <<-EOT
    Realm OTP policy. SHA1 / 6 digits / 30s is what every offline authenticator
    app (FreeOTP, Google Authenticator, Microsoft Authenticator) implements;
    Google Authenticator ignores `algorithm` in the otpauth URI on some
    versions and silently generates SHA1 codes, so SHA256 locks those users out.
    Moving to SHA256 is a policy change plus a re-enrolment of every user —
    docs/implemented/mfa-totp.md, "Moving to SHA256".
  EOT
  type = object({
    type              = string
    algorithm         = string
    digits            = number
    period            = number
    look_ahead_window = number
  })
  default = {
    type              = "totp"
    algorithm         = "HmacSHA1"
    digits            = 6
    period            = 30
    look_ahead_window = 1
  }
}

variable "realm_lockout_seconds" {
  description = "Brute-force temporary lockout. wait_increment and max_failure_wait are both set to this, so every lockout is the same length (flat, not escalating)."
  type        = number
  default     = 900
}
