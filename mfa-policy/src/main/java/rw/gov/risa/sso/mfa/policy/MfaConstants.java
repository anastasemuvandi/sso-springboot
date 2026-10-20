package rw.gov.risa.sso.mfa.policy;

import java.util.List;

/**
 * Names shared by the Keycloak {@code gorsso-mfa} provider, the wrapper and the flow
 * installer. Every string here is a contract with Keycloak state (realm/client/user
 * attributes, roles, required actions, provider ids), so it lives in one place.
 *
 * <p>Ported from the reference {@code app/core/mfa_policy.py} and
 * {@code keycloak/extensions/gorsso-mfa/MfaRequirement.java}.
 */
public final class MfaConstants {

    private MfaConstants() {
    }

    // ---- realm attributes ------------------------------------------------------------

    /** Realm attribute that decides: {@code off} | {@code per_client} | {@code all}. */
    public static final String MODE_ATTRIBUTE = "mfa_totp_mode";
    /** Legacy single switch, read only while {@link #MODE_ATTRIBUTE} is unset or blank. */
    public static final String LEGACY_ATTRIBUTE = "mfa_totp_enforced";
    /**
     * Set when the mode first becomes {@code all} and the bulk CONFIGURE_TOTP enrolment
     * starts; its presence makes that enrolment a one-time act.
     */
    public static final String FIRST_ENABLED_ATTRIBUTE = "mfa_totp_first_enabled_at";
    /** Realm attribute mapping acr values to levels of authentication. */
    public static final String ACR_LOA_MAP_ATTRIBUTE = "acr.loa.map";
    /** Exact value written by Terraform ({@code jsonencode({"1"=1,"2"=2})}) and the installer. */
    public static final String ACR_LOA_MAP_VALUE = "{\"1\":1,\"2\":2}";

    // ---- client attribute ------------------------------------------------------------

    /** Client attribute, read only in per-client mode. Unset = REQUIRED (secure default). */
    public static final String CLIENT_ATTRIBUTE = "sso.client.mfa_required";

    // ---- exemption -------------------------------------------------------------------

    /**
     * Realm role that exempts a person from TOTP. Keycloak's flow checks it with
     * "Condition - user role" (negated); the wrapper reads it from the token's
     * {@code realm_access.roles}. Granted only through {@code POST /realm/mfa/exemptions}.
     */
    public static final String EXEMPT_ROLE = "mfa-exempt";
    public static final String EXEMPT_ROLE_DESCRIPTION =
            "Exempt from the realm-wide TOTP requirement. Super admins only, through the wrapper, "
                    + "which records the justification in the user attribute mfa_exempt_reason.";
    /** User attributes written with every exemption (who asked / why, who granted, when). */
    public static final String EXEMPT_REASON_ATTRIBUTE = "mfa_exempt_reason";
    public static final String EXEMPT_GRANTED_BY_ATTRIBUTE = "mfa_exempt_granted_by";
    public static final String EXEMPT_GRANTED_AT_ATTRIBUTE = "mfa_exempt_granted_at";
    public static final List<String> EXEMPT_ATTRIBUTES =
            List.of(EXEMPT_REASON_ATTRIBUTE, EXEMPT_GRANTED_BY_ATTRIBUTE, EXEMPT_GRANTED_AT_ATTRIBUTE);
    public static final int MIN_REASON_LENGTH = 15;
    public static final int MAX_REASON_LENGTH = 500;

    // ---- required actions ------------------------------------------------------------

    public static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";
    /** Keycloak's alias for the recovery-code setup action (the brief calls it RECOVERY_AUTHN_CODES). */
    public static final String CONFIGURE_RECOVERY_AUTHN_CODES = "CONFIGURE_RECOVERY_AUTHN_CODES";
    public static final List<String> REQUIRED_ACTIONS = List.of(CONFIGURE_TOTP, CONFIGURE_RECOVERY_AUTHN_CODES);

    // ---- credential types ------------------------------------------------------------

    public static final String OTP_CREDENTIAL_TYPE = "otp";
    public static final String RECOVERY_CODES_CREDENTIAL_TYPE = "recovery-authn-codes";

    // ---- levels of authentication / acr ------------------------------------------------

    public static final int LOA_PASSWORD = 1;
    public static final int LOA_TOTP = 2;
    /** acr claim value for a password-only login. */
    public static final String ACR_PASSWORD = "1";
    /** acr claim value for a login that presented TOTP (or a recovery code). */
    public static final String ACR_TOTP = "2";
    /** Default max age of a level for SSO-cookie logins (Terraform mfa_loa_max_age_seconds). */
    public static final int LOA_MAX_AGE_SECONDS = 36000;

    // ---- flow and providers ------------------------------------------------------------

    /** Browser flow built by infra/terraform/mfa.tf (and the wrapper installer). */
    public static final String MFA_FLOW_ALIAS = "browser-totp";
    /** Stock browser flow, used to unbind during a rebuild. */
    public static final String DEFAULT_BROWSER_FLOW = "browser";
    /** Classpath location of the declarative flow definition. */
    public static final String FLOW_DEFINITION_RESOURCE = "/gorsso/mfa-flow.json";

    public static final String PROVIDER_STEP_UP = "gorsso-mfa-step-up";
    public static final String PROVIDER_REALM_ATTRIBUTE_CONDITION = "gorsso-condition-realm-attribute";
    public static final String PROVIDER_CREDENTIAL_CONDITION = "gorsso-condition-credential";
    public static final String PROVIDER_RECOVERY_REENROL = "gorsso-recovery-reenrol";
    public static final String PROVIDER_AUDIT_LISTENER = "gorsso-audit";
    /** Authenticator providers the flow needs from the gorsso-mfa JAR. */
    public static final List<String> REQUIRED_AUTHENTICATORS = List.of(
            PROVIDER_STEP_UP, PROVIDER_REALM_ATTRIBUTE_CONDITION, PROVIDER_CREDENTIAL_CONDITION,
            PROVIDER_RECOVERY_REENROL);
    /** Built-in form for recovery codes; exists only with the {@code recovery-codes} feature. */
    public static final String RECOVERY_CODE_FORM = "auth-recovery-authn-code-form";

    /** Keycloak event types enabled for the realm so the listener sees them. */
    public static final List<String> EVENT_TYPES = List.of(
            "UPDATE_TOTP", "REMOVE_TOTP", "UPDATE_CREDENTIAL", "UPDATE_CREDENTIAL_ERROR",
            "REMOVE_CREDENTIAL", "LOGIN", "LOGIN_ERROR");
}
