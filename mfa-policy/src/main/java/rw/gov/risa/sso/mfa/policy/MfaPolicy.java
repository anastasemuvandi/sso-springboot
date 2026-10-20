package rw.gov.risa.sso.mfa.policy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE TOTP rule — one implementation used by the Keycloak {@code gorsso-mfa} provider
 * (step-up authenticator and realm-attribute condition) and by the wrapper (switch,
 * per-client requirement, password-grant gate, bulk enrolment, exemptions).
 * Pure: plain strings and maps in, no Keycloak or Spring types.
 *
 * <p>The realm decides the mode ({@value MfaConstants#MODE_ATTRIBUTE}):
 * <ul>
 *   <li>{@code off} — nobody is asked, on any client;</li>
 *   <li>{@code all} — every client requires it;</li>
 *   <li>{@code per_client} — a client requires it unless its attribute
 *       {@value MfaConstants#CLIENT_ATTRIBUTE} is {@code false} (trimmed, any case).
 *       Unset or blank = required.</li>
 * </ul>
 * An unset/blank mode falls back to the legacy {@value MfaConstants#LEGACY_ATTRIBUTE}:
 * {@code false} (trimmed, any case) means {@code off}, anything else including unset means
 * {@code all}. An unrecognised mode reads as {@code all}: fail closed, never open.
 *
 * <p>Port note: the reference had two readings of the legacy attribute — Keycloak's
 * {@code MfaRequirement.mode} (only "false" is off) and the Python
 * {@code is_enforced} (only "true" is on, so a junk value like "yes" was off). They
 * disagreed for junk values. This port uses Keycloak's reading everywhere because
 * Keycloak is the enforcer and it fails closed; {@link #attributeMatches} keeps the
 * generic "expected value" comparison of the reference {@code isOn}.
 */
public final class MfaPolicy {

    private MfaPolicy() {
    }

    public static final String DELEGATED_OFF_REFUSAL =
            "Only a super admin can turn two-factor authentication off for a client. "
                    + "An institution admin may only require it.";

    public static final String PASSWORD_GRANT_REFUSAL =
            "Two-factor authentication is required for this account, and a password "
                    + "sign-in cannot carry a TOTP code. Sign in through the browser "
                    + "(/oauth2/authorize) instead.";

    // ---------------------------------------------------------------------------------
    // Mode
    // ---------------------------------------------------------------------------------

    /** The effective realm mode from the raw realm attributes (either may be null). */
    public static MfaMode effectiveMode(String modeRaw, String legacyRaw) {
        if (!isBlank(modeRaw)) {
            return MfaMode.fromValue(modeRaw).orElse(MfaMode.ALL);
        }
        return legacyEnforced(legacyRaw) ? MfaMode.ALL : MfaMode.OFF;
    }

    /** {@link #effectiveMode} as its attribute value — port of {@code MfaRequirement.mode}. */
    public static String mode(String modeRaw, String legacyRaw) {
        return effectiveMode(modeRaw, legacyRaw).value();
    }

    /** The effective mode read from a realm attribute map (values String or List). */
    public static MfaMode effectiveMode(Map<String, ?> realmAttributes) {
        return effectiveMode(attribute(realmAttributes, MfaConstants.MODE_ATTRIBUTE).orElse(null),
                attribute(realmAttributes, MfaConstants.LEGACY_ATTRIBUTE).orElse(null));
    }

    /**
     * The legacy switch: unset or blank = ON (enforcement is the safe default);
     * {@code false} (trimmed, any case) = off; any other value = on.
     */
    public static boolean legacyEnforced(String raw) {
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }

    /**
     * Generic attribute comparison used by the realm-attribute condition's config
     * ({@code expected}, {@code when_unset}): unset or blank falls back to
     * {@code whenUnset}; otherwise trimmed, case-insensitive equality. Port of
     * {@code RealmAttributeCondition.isOn}.
     */
    public static boolean attributeMatches(String value, String expected, boolean whenUnset) {
        if (isBlank(value)) {
            return whenUnset;
        }
        return value.trim().equalsIgnoreCase(expected);
    }

    /**
     * Validates a mode a super admin asked for. Trimmed and lower-cased.
     *
     * @throws IllegalArgumentException for anything but off / per_client / all
     */
    public static MfaMode requestedMode(String value) {
        return MfaMode.fromValue(value == null ? "" : value).orElseThrow(() ->
                new IllegalArgumentException("mode must be one of off, per_client, all"));
    }

    /** {@code "true"} / {@code "false"} as written to the legacy switch and the client attribute. */
    public static String encode(boolean value) {
        return value ? "true" : "false";
    }

    // ---------------------------------------------------------------------------------
    // Per client
    // ---------------------------------------------------------------------------------

    /** Per-client mode: unset, blank, or anything but {@code false} (trimmed, any case) = required. */
    public static boolean clientRequires(String clientValue) {
        return clientValue == null || !"false".equalsIgnoreCase(clientValue.trim());
    }

    /** Does TOTP apply to a login to a client with this attribute value, under this mode? */
    public static boolean applies(MfaMode mode, String clientValue) {
        return switch (mode == null ? MfaMode.ALL : mode) {
            case OFF -> false;
            case PER_CLIENT -> clientRequires(clientValue);
            case ALL -> true;
        };
    }

    /**
     * The single decision both Keycloak providers make: realm mode (with legacy
     * fallback) + the client's own value. Port of {@code MfaRequirement.applies}.
     */
    public static boolean applies(String modeRaw, String legacyRaw, String clientValue) {
        return applies(effectiveMode(modeRaw, legacyRaw), clientValue);
    }

    /** Optional-friendly overload of {@link #applies(String, String, String)}. */
    public static boolean applies(Optional<String> modeRaw, Optional<String> legacyRaw, Optional<String> clientValue) {
        return applies(modeRaw.orElse(null), legacyRaw.orElse(null), clientValue.orElse(null));
    }

    /**
     * The client's {@value MfaConstants#CLIENT_ATTRIBUTE} from a client representation
     * map ({@code {"attributes": {...}}}), or empty.
     */
    public static Optional<String> clientValue(Map<String, ?> clientRepresentation) {
        if (clientRepresentation == null) {
            return Optional.empty();
        }
        Object attrs = clientRepresentation.get("attributes");
        return attrs instanceof Map<?, ?> m ? attribute(m, MfaConstants.CLIENT_ATTRIBUTE) : Optional.empty();
    }

    /** A delegated admin may turn 2FA ON for their own client, never OFF. Empty = allowed. */
    public static Optional<String> delegatedChangeRefusal(boolean required) {
        return required ? Optional.empty() : Optional.of(DELEGATED_OFF_REFUSAL);
    }

    // ---------------------------------------------------------------------------------
    // Exemptions
    // ---------------------------------------------------------------------------------

    /**
     * The justification an exemption must carry: whitespace collapsed, 15..500 characters.
     *
     * @throws IllegalArgumentException when too short or too long
     */
    public static String validateReason(String reason) {
        String text = reason == null ? "" : String.join(" ", reason.trim().split("\\s+")).trim();
        if (text.length() < MfaConstants.MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("A justification of at least " + MfaConstants.MIN_REASON_LENGTH
                    + " characters is required (who asked, why TOTP cannot be used, until when).");
        }
        if (text.length() > MfaConstants.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "The justification is limited to " + MfaConstants.MAX_REASON_LENGTH + " characters.");
        }
        return text;
    }

    /** Whether a set of realm role names contains {@value MfaConstants#EXEMPT_ROLE}. */
    public static boolean isExempt(Collection<String> realmRoles) {
        return realmRoles != null && realmRoles.contains(MfaConstants.EXEMPT_ROLE);
    }

    /** Whether token claims carry {@code realm_access.roles} containing the exempt role. */
    public static boolean isExemptClaims(Map<String, ?> claims) {
        return realmRoles(claims).contains(MfaConstants.EXEMPT_ROLE);
    }

    /**
     * True when a generic role-assignment path names the exempt role; the caller must refuse
     * (400) — exemptions are granted only through {@code POST /realm/mfa/exemptions}.
     */
    public static boolean namesExemptRole(Collection<String> roleNames) {
        return isExempt(roleNames);
    }

    // ---------------------------------------------------------------------------------
    // Tokens: acr and the password-grant door
    // ---------------------------------------------------------------------------------

    /** Did the login behind this acr value include a TOTP (or recovery) code? acr >= 2. */
    public static boolean presentedTotp(Object acr) {
        if (acr == null || acr instanceof Boolean) {
            return false;
        }
        try {
            return Integer.parseInt(String.valueOf(acr).trim()) >= MfaConstants.LOA_TOTP;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@link #presentedTotp(Object)} read from the token's {@code acr} claim. */
    public static boolean presentedTotpClaims(Map<String, ?> claims) {
        return claims != null && presentedTotp(claims.get("acr"));
    }

    /**
     * Why a token must not leave by a password-grant door, or empty. {@code enforced} is
     * {@link SwitchState#appliesTo} for the client (or {@link SwitchState#effective}).
     * The exemption is read from the token Keycloak just issued.
     */
    public static Optional<String> passwordGrantRefusal(Map<String, ?> claims, boolean enforced) {
        if (!enforced || isExemptClaims(claims) || presentedTotpClaims(claims)) {
            return Optional.empty();
        }
        return Optional.of(PASSWORD_GRANT_REFUSAL);
    }

    // ---------------------------------------------------------------------------------
    // Bulk enrolment
    // ---------------------------------------------------------------------------------

    /**
     * Whether the bulk CONFIGURE_TOTP job should touch this user. Empty = assign; otherwise
     * why it is skipped. Idempotent: a user already enrolled or already pending is skipped.
     */
    public static Optional<BulkSkipReason> bulkEnrolmentSkip(boolean enabled, String username,
                                                             boolean serviceAccountLinked,
                                                             Collection<String> requiredActions,
                                                             boolean hasOtp, boolean exempt) {
        if (!enabled) {
            return Optional.of(BulkSkipReason.DISABLED);
        }
        if (serviceAccountLinked || (username != null && username.startsWith("service-account-"))) {
            return Optional.of(BulkSkipReason.SERVICE_ACCOUNT);
        }
        if (exempt) {
            return Optional.of(BulkSkipReason.EXEMPT);
        }
        if (hasOtp) {
            return Optional.of(BulkSkipReason.ALREADY_ENROLLED);
        }
        if (requiredActions != null && requiredActions.contains(MfaConstants.CONFIGURE_TOTP)) {
            return Optional.of(BulkSkipReason.ALREADY_PENDING);
        }
        return Optional.empty();
    }

    /**
     * {@link #bulkEnrolmentSkip} over a Keycloak user representation map
     * ({@code enabled} default true, {@code username}, {@code serviceAccountClientLink},
     * {@code requiredActions}).
     */
    public static Optional<BulkSkipReason> bulkEnrolmentSkip(Map<String, ?> user, boolean hasOtp, boolean exempt) {
        Object enabled = user.get("enabled");
        Object link = user.get("serviceAccountClientLink");
        Object username = user.get("username");
        return bulkEnrolmentSkip(
                !(enabled instanceof Boolean b) || b,
                username == null ? "" : String.valueOf(username),
                link != null && !String.valueOf(link).isEmpty(),
                stringList(user.get("requiredActions")),
                hasOtp, exempt);
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    /**
     * A single attribute value from a Keycloak attribute map whose values are either
     * strings (realm, client) or lists of strings (user): the first element of a list.
     */
    public static Optional<String> attribute(Map<?, ?> attributes, String name) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object v = attributes.get(name);
        if (v instanceof List<?> l) {
            v = l.isEmpty() ? null : l.get(0);
        }
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    /** Parses a level-of-authentication config value; empty for blank or non-numeric. */
    public static Optional<Integer> parseLevel(String raw) {
        if (isBlank(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static List<String> realmRoles(Map<String, ?> claims) {
        if (claims == null) {
            return List.of();
        }
        Object ra = claims.get("realm_access");
        if (!(ra instanceof Map<?, ?> m)) {
            return List.of();
        }
        return stringList(m.get("roles"));
    }

    private static List<String> stringList(Object o) {
        if (o instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
