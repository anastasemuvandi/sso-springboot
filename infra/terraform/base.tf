# =============================================================================
# Base setup — the realm every institution is onboarded INTO.
#
# Scope note. This manages `government-internal` only: the shared institution
# realm of ADR-0001. The other two tier realms (`government-citizens`,
# `government-admin`) hold no institutions and are still provisioned from
# `configs/realms/*.json`; bringing them under IaC is a separate, unrelated
# change and is deliberately not bundled here.
#
# Also deliberately NOT managed here:
#
#   sso-client / sso-admin-ui  — the wrapper's own control-plane clients. Their
#     realm-management role grants are self-healed on every wrapper start by
#     reconcile_service_account_roles(), and their secrets live in `.env`.
#     Terraform would fight the reconciler on one and rotate the other out from
#     under a running service. They stay imperative on purpose.
# =============================================================================

resource "keycloak_realm" "internal" {
  count = var.manage_realm ? 1 : 0

  realm        = var.realm
  enabled      = true
  display_name = var.realm_display_name

  # Materialises the `admin-permissions` client that fgap_v2.tf writes into
  # (ADR-0002 §6 Step 3). Keycloak 26.2+ only.
  #
  # NOTE: this is only reachable when `manage_realm` is true. Where the realm is
  # imported from configs/realms/*.json rather than managed here, the switch has
  # to be flipped on the realm itself before `enable_fgap_v2` can apply — the
  # data source below cannot set it.
  admin_permissions_enabled = var.enable_fgap_v2

  # Keycloak 26.2 Organizations (the spike seed turns them on). Unmanaged
  # before, so a plan against a 26 realm proposed switching them off; the
  # browser-totp flow keeps the Organization identity-first step that needs it.
  organizations_enabled = var.realm_organizations_enabled

  login_theme   = var.realm_login_theme
  account_theme = var.realm_account_theme
  admin_theme   = var.realm_admin_theme
  email_theme   = var.realm_email_theme

  # 'none' only holds while the target is the HTTP-only staging box. Flip to
  # 'external' with the TLS cutover.
  ssl_required = var.realm_ssl_required

  # Staff accounts come from IPPIS, never self-registration.
  registration_allowed           = false
  registration_email_as_username = false
  reset_password_allowed         = true
  remember_me                    = false
  verify_email                   = true
  login_with_email_allowed       = true
  duplicate_emails_allowed       = false
  edit_username_allowed          = false

  password_policy = var.realm_password_policy

  access_token_lifespan    = var.realm_access_token_lifespan
  sso_session_idle_timeout = var.realm_sso_session_idle_timeout
  sso_session_max_lifespan = var.realm_sso_session_max_lifespan

  revoke_refresh_token    = true
  refresh_token_max_reuse = 0

  internationalization {
    # English and Kinyarwanda (spec §3, the Keycloakify theme's two languages).
    supported_locales = ["en", "rw"]
    default_locale    = "en"
  }

  # Realm OTP policy — see var.realm_otp_policy for why it is SHA1. The label
  # authenticator apps show is the realm DISPLAY NAME: Keycloak has no separate
  # OTP issuer setting (OTPPolicy.getKeyURI), so it is not configured here.
  otp_policy {
    type              = var.realm_otp_policy.type
    algorithm         = var.realm_otp_policy.algorithm
    digits            = var.realm_otp_policy.digits
    period            = var.realm_otp_policy.period
    look_ahead_window = var.realm_otp_policy.look_ahead_window
  }

  attributes = {
    # The TOTP mode (mfa.tf): off | per_client | all. Written at creation,
    # then ignored — see `lifecycle` below.
    mfa_totp_mode = var.mfa_totp_mode_initial
    # ACR <-> level of authentication: acr "1" = password only, "2" = TOTP was
    # presented. Applications check `acr` to know MFA happened, whatever the
    # switch says.
    "acr.loa.map" = jsonencode({ "1" = 1, "2" = 2 })
  }

  security_defenses {
    brute_force_detection {
      permanent_lockout      = false
      max_login_failures     = var.realm_login_failure_factor
      wait_increment_seconds = var.realm_lockout_seconds
      # Flat 900 s after `max_login_failures` (spec §3). The quick-login check is off (0 ms):
      # with it on, two failures inside one second already lock the account for the
      # quick-login wait, before failureFactor is reached (verified on 26.2.5).
      quick_login_check_milli_seconds  = 0
      minimum_quick_login_wait_seconds = var.realm_lockout_seconds
      max_failure_wait_seconds         = var.realm_lockout_seconds
      failure_reset_time_seconds       = 43200
    }
  }

  lifecycle {
    # Deleting the realm would destroy every user, session and credential in it.
    # Removing this block is a conscious, reviewed act — not a side effect of a
    # refactor that renames the resource.
    prevent_destroy = true

    # The TOTP mode belongs to the super admin at runtime (wrapper, audited
    # with the actor). A later apply must not silently change it. The legacy
    # mfa_totp_enforced is ignored too, in case a realm still carries it.
    ignore_changes = [attributes["mfa_totp_mode"], attributes["mfa_totp_enforced"]]
  }
}

data "keycloak_realm" "internal" {
  count = var.manage_realm ? 0 : 1
  realm = var.realm
}

locals {
  realm_id = var.manage_realm ? keycloak_realm.internal[0].id : data.keycloak_realm.internal[0].id
}

# -----------------------------------------------------------------------------
# Audit / event capture. The audit-service builds a hash chain from application
# events, but Keycloak's own login and admin events are the independent record
# used to detect a change made outside the wrapper — including a grant made by
# hand in the admin console, which is exactly what `make delegated-check` hunts.
# -----------------------------------------------------------------------------

resource "keycloak_realm_events" "internal" {
  count    = var.manage_realm ? 1 : 0
  realm_id = local.realm_id

  events_enabled    = true
  events_expiration = 7776000 # 90 days

  admin_events_enabled         = true
  admin_events_details_enabled = true

  # `gorsso-audit` forwards the MFA events (TOTP enrol/remove, second-factor
  # failures, recovery-code use) to the audit service. It is a provider in the
  # gorsso-mfa JAR, so it is listed only when that JAR is expected to be there.
  events_listeners = var.enable_mfa_totp ? ["jboss-logging", "gorsso-audit"] : ["jboss-logging"]

  enabled_event_types = concat([
    "LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR",
    "REGISTER", "REGISTER_ERROR",
    "UPDATE_PASSWORD", "UPDATE_PASSWORD_ERROR",
    "UPDATE_TOTP", "REMOVE_TOTP",
    "REFRESH_TOKEN", "REFRESH_TOKEN_ERROR",
    "CODE_TO_TOKEN", "CODE_TO_TOKEN_ERROR",
    "INTROSPECT_TOKEN", "INTROSPECT_TOKEN_ERROR",
    "USER_INFO_REQUEST", "USER_INFO_REQUEST_ERROR",
    "TOKEN_EXCHANGE", "TOKEN_EXCHANGE_ERROR",
    "CLIENT_LOGIN", "CLIENT_LOGIN_ERROR",
    "REVOKE_GRANT", "PERMISSION_TOKEN",
    # TOTP and recovery-code (de)enrolment, with `credential_type` in the
    # details.
    "UPDATE_CREDENTIAL", "UPDATE_CREDENTIAL_ERROR", "REMOVE_CREDENTIAL",
  ])
}

# -----------------------------------------------------------------------------
# Platform-wide realm roles.
#
# These are the tiers `require_admin_tier` gates the realm-wide admin surface
# on. They are the roles an institution admin must NEVER hold — the guardrail in
# configs/delegated-permissions/templates.json lists them by name. Keeping them
# here, in one place, means the guardrail's forbidden list has a visible,
# reviewable counterpart.
# -----------------------------------------------------------------------------

resource "keycloak_role" "platform" {
  for_each = var.manage_platform_roles ? var.platform_realm_roles : {}

  realm_id    = local.realm_id
  name        = each.key
  description = each.value
}

resource "keycloak_default_roles" "internal" {
  count = var.manage_realm ? 1 : 0

  realm_id      = local.realm_id
  default_roles = var.default_realm_roles

  depends_on = [keycloak_role.platform]
}
