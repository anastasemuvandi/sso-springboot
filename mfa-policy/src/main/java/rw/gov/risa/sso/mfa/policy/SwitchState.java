package rw.gov.risa.sso.mfa.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The realm's TOTP switch as the wrapper sees it: the raw attributes plus the bound
 * browser flow. "Enforced" needs BOTH the attribute and the flow — on a realm whose
 * browser flow is not {@code browser-totp} nothing asks for a code, so refusing
 * password grants there would lock people out of a requirement that does not exist.
 *
 * @param legacyRaw   raw {@value MfaConstants#LEGACY_ATTRIBUTE} (nullable)
 * @param browserFlow the realm's {@code browserFlow} (nullable)
 * @param modeRaw     raw {@value MfaConstants#MODE_ATTRIBUTE} (nullable)
 */
public record SwitchState(String legacyRaw, String browserFlow, String modeRaw) {

    /** From a realm representation map ({@code browserFlow}, {@code attributes}). */
    public static SwitchState fromRealm(Map<String, ?> realm) {
        Object attrs = realm == null ? null : realm.get("attributes");
        Map<?, ?> a = attrs instanceof Map<?, ?> m ? m : Map.of();
        Object flow = realm == null ? null : realm.get("browserFlow");
        return new SwitchState(
                MfaPolicy.attribute(a, MfaConstants.LEGACY_ATTRIBUTE).orElse(null),
                flow == null ? null : String.valueOf(flow),
                MfaPolicy.attribute(a, MfaConstants.MODE_ATTRIBUTE).orElse(null));
    }

    /** Effective mode (fail closed). */
    public MfaMode mode() {
        return MfaPolicy.effectiveMode(modeRaw, legacyRaw);
    }

    /** TOTP is on for at least some clients (mode is not {@code off}). Legacy "enforced". */
    public boolean attributeOn() {
        return mode() != MfaMode.OFF;
    }

    /** The realm's browser flow is {@value MfaConstants#MFA_FLOW_ALIAS}. */
    public boolean flowReady() {
        return MfaConstants.MFA_FLOW_ALIAS.equals(browserFlow);
    }

    /** TOTP is actually being required at login, on some client. */
    public boolean effective() {
        return flowReady() && attributeOn();
    }

    /** TOTP is required at login to a client with this attribute value. */
    public boolean appliesTo(String clientValue) {
        return flowReady() && MfaPolicy.applies(mode(), clientValue);
    }

    /**
     * The state fields of the reference {@code GET /realm/mfa} response, snake_case keys:
     * {@code mode, raw_mode, enforced, effective, raw, browser_flow, flow_ready}. The caller
     * adds {@code first_enabled_at} and {@code exempt_count}.
     */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode().value());
        out.put("raw_mode", modeRaw);
        out.put("enforced", attributeOn());
        out.put("effective", effective());
        out.put("raw", legacyRaw);
        out.put("browser_flow", browserFlow);
        out.put("flow_ready", flowReady());
        return out;
    }
}
