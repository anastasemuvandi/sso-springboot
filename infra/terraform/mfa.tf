# =============================================================================
# Realm-wide TOTP (2FA), behind one switch — docs/implemented/mfa-totp.md
#
# REQUIRES KEYCLOAK 26.2+ WITH THE gorsso-mfa PROVIDER AND `recovery-codes`.
# Seven of the executions below name providers that ship in
# keycloak/extensions/gorsso-mfa (the JAR goes in /opt/keycloak/providers/
# before `kc.sh build`), and the recovery-code form is a PREVIEW feature in
# 26.2.5 — KC_FEATURES must include `recovery-codes`. An apply against a
# Keycloak without them fails on the first execution that names one. Hence
# `enable_mfa_totp` defaults to false.
#
# WHAT DECIDES IT. The realm attribute `mfa_totp_mode` — `off`, `all` (every
# client) or `per_client` (a client requires TOTP unless its attribute
# `sso.client.mfa_required` is "false"; unset = required). Unset mode falls
# back to the legacy `mfa_totp_enforced` (false -> off, else all). Read on every
# login by `gorsso-condition-realm-attribute` and `gorsso-mfa-step-up`
# (MfaRequirement.java), so a change takes effect at the next login with no
# restart. Terraform writes the mode ONCE, at creation (`mfa_totp_mode_initial`),
# and then ignores it: the super admin owns it through the wrapper, which audits
# every change with its actor. See base.tf, `ignore_changes`.
#
# THE FLOW. `browser-totp` is the built-in 26.2 browser flow, rebuilt here
# (Terraform cannot copy a flow) with its `forms` sub-flow replaced:
#
#   GoRSSO - request TOTP for this client    REQUIRED   raises the requested level to 2
#   main                                     REQUIRED
#   Cookie                                   ALTERNATIVE
#   Kerberos                                 DISABLED
#   Identity Provider Redirector             ALTERNATIVE
#   Organization                             ALTERNATIVE   (enable_organization_login)
#     Conditional Organization               CONDITIONAL
#       Condition - user configured          REQUIRED
#       Organization Identity-First Login    ALTERNATIVE
#   forms                                    ALTERNATIVE
#   (Cookie … forms sit inside `main`, one level down; shown flat for width.)
#     password                               CONDITIONAL
#       Condition - Level of Authentication  REQUIRED   level 1  -> acr "1"
#       Username Password Form               REQUIRED
#     2FA                                    CONDITIONAL
#       Condition - user role                REQUIRED   mfa-exempt, NEGATED
#       Condition - GoRSSO realm attribute   REQUIRED   mfa_totp_enforced, records level 2 -> acr "2"
#       Condition - Level of Authentication  DISABLED   level 2  (declaration only, see below)
#       enrol                                CONDITIONAL
#         Condition - GoRSSO user has credential REQUIRED  otp, present = false
#         OTP Form                           REQUIRED   -> CONFIGURE_TOTP (setup required)
#         enrol recovery codes               CONDITIONAL
#           Condition - GoRSSO user has credential REQUIRED  recovery-authn-codes, present = false
#           Recovery Authentication Code Form REQUIRED  -> CONFIGURE_RECOVERY_AUTHN_CODES
#       verify                               CONDITIONAL
#         Condition - GoRSSO user has credential REQUIRED  otp, present = true
#         2FA methods                        REQUIRED
#           OTP Form                         ALTERNATIVE
#           recovery                         ALTERNATIVE
#             Recovery Authentication Code Form REQUIRED
#             GoRSSO - re-enrol TOTP         REQUIRED
#
# Four things about that shape that are not obvious:
#
#  * The step-up authenticator exists for SSO under per-client mode. A session
#    from a client that did not require TOTP is level 1, and Keycloak's cookie
#    authenticator lets it into ANY client unless the requested level is higher
#    — no client here sends acr_values. Raising it to 2 for clients that
#    require TOTP makes the cookie step up to the OTP page instead. It has to
#    run before the cookie, and a REQUIRED sibling beside ALTERNATIVEs makes
#    Keycloak ignore the alternatives, hence the `main` wrapper sub-flow.
#  * The exemption condition comes FIRST. Keycloak stops at the first condition
#    that fails, so an exempt user never reaches the realm-attribute condition,
#    which is the one that marks the attempt's events as second-factor.
#  * A user with no OTP credential is not skipped: in `enrol` the OTP Form is
#    REQUIRED and not "configured for" them, so Keycloak raises CONFIGURE_TOTP
#    (and recovery-code setup) and the login does not complete until they
#    enrol. No grace period. The split on "has an OTP credential" is needed
#    because with OTP Form and the recovery branch as ALTERNATIVES, an
#    unconfigured OTP Form is skipped and the engine falls through to the
#    recovery branch — found on 26.2.5, where it asked a never-enrolled user
#    for recovery-code setup and reported a recovery-code login.
#  * The DISABLED level-2 condition is never evaluated. It exists because
#    Keycloak reads each level's max-age from the LoA conditions present in the
#    browser flow, and without one for level 2 an SSO-cookie login later in the
#    session would drop `acr` back to 1. It cannot be the gate itself: with no
#    `acr_values` requested it evaluates to false once level 1 is reached, and
#    no client of this realm requests one.
#
# NOT TOUCHED: the direct-grant and client-credentials flows. Service accounts
# are out of scope, and a password grant cannot carry a second factor at all —
# which is why the wrapper refuses one for an MFA-bound user while the switch
# is on (mfa-policy: MfaPolicy, shared by the wrapper and the Keycloak SPI); nothing here changes that flow.
# =============================================================================

locals {
  mfa     = var.enable_mfa_totp
  mfa_ids = local.mfa ? toset(["on"]) : toset([])

  mfa_flow_alias = "browser-totp"
}

resource "keycloak_role" "mfa_exempt" {
  for_each = local.mfa_ids

  realm_id    = local.realm_id
  name        = "mfa-exempt"
  description = "Exempt from the realm-wide TOTP requirement. Super admins only, through the wrapper, which records the justification in the user attribute mfa_exempt_reason."
}

# -----------------------------------------------------------------------------
# Required actions
# -----------------------------------------------------------------------------

resource "keycloak_required_action" "configure_totp" {
  for_each = local.mfa_ids

  realm_id       = local.realm_id
  alias          = "CONFIGURE_TOTP"
  name           = "Configure OTP"
  enabled        = true
  default_action = var.mfa_default_actions_for_new_users
  priority       = 10
}

resource "keycloak_required_action" "recovery_codes" {
  for_each = local.mfa_ids

  realm_id = local.realm_id
  # The brief calls it RECOVERY_AUTHN_CODES; Keycloak's alias is this.
  alias          = "CONFIGURE_RECOVERY_AUTHN_CODES"
  name           = "Recovery Authentication Codes"
  enabled        = true
  default_action = var.mfa_default_actions_for_new_users
  priority       = 20
}

# -----------------------------------------------------------------------------
# browser-totp
#
# `priority` fixes the order explicitly (provider v5, Keycloak 25+). depends_on
# chains are kept as well, because the provider appends in creation order and a
# parallel apply would otherwise create siblings in whatever order it likes
# before the priorities are written.
# -----------------------------------------------------------------------------

resource "keycloak_authentication_flow" "browser_totp" {
  for_each = local.mfa_ids

  realm_id    = local.realm_id
  alias       = local.mfa_flow_alias
  description = "Browser flow with the realm-wide TOTP requirement (mfa_totp_enforced). Managed by infra/terraform/mfa.tf."
  provider_id = "basic-flow"
}

resource "keycloak_authentication_execution" "step_up" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_flow.browser_totp["on"].alias
  authenticator     = "gorsso-mfa-step-up"
  requirement       = "REQUIRED"
  priority          = 5
}

resource "keycloak_authentication_subflow" "main" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_flow.browser_totp["on"].alias
  alias             = "${local.mfa_flow_alias} main"
  provider_id       = "basic-flow"
  requirement       = "REQUIRED"
  priority          = 10

  depends_on = [keycloak_authentication_execution.step_up]
}

resource "keycloak_authentication_execution" "cookie" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.main["on"].alias
  authenticator     = "auth-cookie"
  requirement       = "ALTERNATIVE"
  priority          = 10
}

resource "keycloak_authentication_execution" "kerberos" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.main["on"].alias
  authenticator     = "auth-spnego"
  requirement       = "DISABLED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.cookie]
}

resource "keycloak_authentication_execution" "idp_redirector" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.main["on"].alias
  authenticator     = "identity-provider-redirector"
  requirement       = "ALTERNATIVE"
  priority          = 25

  depends_on = [keycloak_authentication_execution.kerberos]
}

# ---- Organization (identity-first), as in the built-in 26.2 browser flow ----

resource "keycloak_authentication_subflow" "organization" {
  for_each = local.mfa && var.enable_organization_login ? toset(["on"]) : toset([])

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.main["on"].alias
  alias             = "${local.mfa_flow_alias} Organization"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  priority          = 26

  depends_on = [keycloak_authentication_execution.idp_redirector]
}

resource "keycloak_authentication_subflow" "organization_conditional" {
  for_each = keycloak_authentication_subflow.organization

  realm_id          = local.realm_id
  parent_flow_alias = each.value.alias
  alias             = "${local.mfa_flow_alias} Conditional Organization"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 10
}

resource "keycloak_authentication_execution" "organization_user_configured" {
  for_each = keycloak_authentication_subflow.organization_conditional

  realm_id          = local.realm_id
  parent_flow_alias = each.value.alias
  authenticator     = "conditional-user-configured"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution" "organization_identity_first" {
  for_each = keycloak_authentication_subflow.organization_conditional

  realm_id          = local.realm_id
  parent_flow_alias = each.value.alias
  authenticator     = "organization"
  requirement       = "ALTERNATIVE"
  priority          = 20

  depends_on = [keycloak_authentication_execution.organization_user_configured]
}

# ---- forms ------------------------------------------------------------------

resource "keycloak_authentication_subflow" "forms" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.main["on"].alias
  alias             = "${local.mfa_flow_alias} forms"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  priority          = 30

  depends_on = [
    keycloak_authentication_execution.idp_redirector,
    keycloak_authentication_subflow.organization,
  ]
}

resource "keycloak_authentication_subflow" "password" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.forms["on"].alias
  alias             = "${local.mfa_flow_alias} password"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 10
}

resource "keycloak_authentication_execution" "loa1" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.password["on"].alias
  authenticator     = "conditional-level-of-authentication"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "loa1" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.loa1["on"].id
  alias        = "${local.mfa_flow_alias}-loa1"
  config = {
    "loa-condition-level" = "1"
    "loa-max-age"         = tostring(var.mfa_loa_max_age_seconds)
  }
}

resource "keycloak_authentication_execution" "username_password" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.password["on"].alias
  authenticator     = "auth-username-password-form"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.loa1]
}

resource "keycloak_authentication_subflow" "two_factor" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.forms["on"].alias
  alias             = "${local.mfa_flow_alias} 2FA"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 20

  depends_on = [keycloak_authentication_subflow.password]
}

resource "keycloak_authentication_execution" "not_exempt" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.two_factor["on"].alias
  authenticator     = "conditional-user-role"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "not_exempt" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.not_exempt["on"].id
  alias        = "${local.mfa_flow_alias}-not-exempt"
  config = {
    condUserRole = keycloak_role.mfa_exempt["on"].name
    negate       = "true"
  }
}

resource "keycloak_authentication_execution" "switch" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.two_factor["on"].alias
  authenticator     = "gorsso-condition-realm-attribute"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.not_exempt]
}

resource "keycloak_authentication_execution_config" "switch" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.switch["on"].id
  alias        = "${local.mfa_flow_alias}-switch"
  # `attribute` is the LEGACY switch, read only while `mfa_totp_mode` is unset.
  config = {
    attribute  = "mfa_totp_enforced"
    expected   = "true"
    when_unset = "true"
    loa        = "2"
  }
}

resource "keycloak_authentication_execution" "loa2_declaration" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.two_factor["on"].alias
  authenticator     = "conditional-level-of-authentication"
  requirement       = "DISABLED"
  priority          = 30

  depends_on = [keycloak_authentication_execution.switch]
}

resource "keycloak_authentication_execution_config" "loa2_declaration" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.loa2_declaration["on"].id
  alias        = "${local.mfa_flow_alias}-loa2"
  config = {
    "loa-condition-level" = "2"
    "loa-max-age"         = tostring(var.mfa_loa_max_age_seconds)
  }
}

# ---- enrol: no OTP credential yet ------------------------------------------

resource "keycloak_authentication_subflow" "enrol" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.two_factor["on"].alias
  alias             = "${local.mfa_flow_alias} enrol"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 40

  depends_on = [keycloak_authentication_execution.loa2_declaration]
}

resource "keycloak_authentication_execution" "enrol_no_otp" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.enrol["on"].alias
  authenticator     = "gorsso-condition-credential"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "enrol_no_otp" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.enrol_no_otp["on"].id
  alias        = "${local.mfa_flow_alias}-no-otp"
  config = {
    credential_type = "otp"
    present         = "false"
  }
}

resource "keycloak_authentication_execution" "enrol_otp_form" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.enrol["on"].alias
  authenticator     = "auth-otp-form"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.enrol_no_otp]
}

# Recovery-code SETUP for someone who has none — never a recovery-code
# PROMPT. A person whose OTP was removed but who still holds codes would
# otherwise be asked to spend one just to enrol a new device.
resource "keycloak_authentication_subflow" "enrol_recovery" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.enrol["on"].alias
  alias             = "${local.mfa_flow_alias} enrol recovery codes"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 30

  depends_on = [keycloak_authentication_execution.enrol_otp_form]
}

resource "keycloak_authentication_execution" "enrol_no_recovery_codes" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.enrol_recovery["on"].alias
  authenticator     = "gorsso-condition-credential"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "enrol_no_recovery_codes" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.enrol_no_recovery_codes["on"].id
  alias        = "${local.mfa_flow_alias}-no-recovery-codes"
  config = {
    credential_type = "recovery-authn-codes"
    present         = "false"
  }
}

resource "keycloak_authentication_execution" "enrol_recovery_form" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.enrol_recovery["on"].alias
  authenticator     = "auth-recovery-authn-code-form"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.enrol_no_recovery_codes]
}

# ---- verify: has an OTP credential -------------------------------------------

resource "keycloak_authentication_subflow" "verify" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.two_factor["on"].alias
  alias             = "${local.mfa_flow_alias} verify"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 50

  depends_on = [keycloak_authentication_subflow.enrol]
}

resource "keycloak_authentication_execution" "verify_has_otp" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.verify["on"].alias
  authenticator     = "gorsso-condition-credential"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "verify_has_otp" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  execution_id = keycloak_authentication_execution.verify_has_otp["on"].id
  alias        = "${local.mfa_flow_alias}-has-otp"
  config = {
    credential_type = "otp"
    present         = "true"
  }
}

resource "keycloak_authentication_subflow" "methods" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.verify["on"].alias
  alias             = "${local.mfa_flow_alias} 2FA methods"
  provider_id       = "basic-flow"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.verify_has_otp]
}

resource "keycloak_authentication_execution" "otp_form" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.methods["on"].alias
  authenticator     = "auth-otp-form"
  requirement       = "ALTERNATIVE"
  priority          = 10
}

resource "keycloak_authentication_subflow" "recovery" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.methods["on"].alias
  alias             = "${local.mfa_flow_alias} recovery"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  priority          = 20

  depends_on = [keycloak_authentication_execution.otp_form]
}

resource "keycloak_authentication_execution" "recovery_code_form" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.recovery["on"].alias
  authenticator     = "auth-recovery-authn-code-form"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution" "recovery_reenrol" {
  for_each = local.mfa_ids

  realm_id          = local.realm_id
  parent_flow_alias = keycloak_authentication_subflow.recovery["on"].alias
  authenticator     = "gorsso-recovery-reenrol"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.recovery_code_form]
}

# Bound last, once every execution exists — binding a half-built flow would
# leave the realm with a browser flow that cannot log anybody in.
resource "keycloak_authentication_bindings" "browser_totp" {
  for_each = local.mfa_ids

  realm_id     = local.realm_id
  browser_flow = keycloak_authentication_flow.browser_totp["on"].alias

  depends_on = [
    keycloak_authentication_execution.step_up,
    keycloak_authentication_execution.cookie,
    keycloak_authentication_execution.kerberos,
    keycloak_authentication_execution.idp_redirector,
    keycloak_authentication_execution.organization_identity_first,
    keycloak_authentication_execution_config.loa1,
    keycloak_authentication_execution.username_password,
    keycloak_authentication_execution_config.not_exempt,
    keycloak_authentication_execution_config.switch,
    keycloak_authentication_execution_config.loa2_declaration,
    keycloak_authentication_execution_config.enrol_no_otp,
    keycloak_authentication_execution_config.enrol_no_recovery_codes,
    keycloak_authentication_execution.enrol_recovery_form,
    keycloak_authentication_execution_config.verify_has_otp,
    keycloak_authentication_execution.otp_form,
    keycloak_authentication_execution.recovery_reenrol,
    keycloak_required_action.configure_totp,
    keycloak_required_action.recovery_codes,
  ]
}
