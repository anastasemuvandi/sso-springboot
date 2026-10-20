package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;

/**
 * Which Keycloak attributes one {@link PersonView} maps to (port of the pure parts of
 * {@code services/ippis_sync.py}).
 *
 * <p>Written: {@code institution} (normalised to the registry slug via {@code ippis_codes},
 * never the raw code), {@code position}, {@code national_id}, {@code phone},
 * {@code ippis_status}. {@code employee_number} is the join key the record was matched by
 * and is never rewritten. <b>The {@code email} property is never written</b> and no account is
 * ever created by this path.
 *
 * <p>User attributes are passed in Keycloak's shape: {@code Map<String, List<String>>}.
 */
public final class IppisAttributeProjection {

    public static final String ATTR_INSTITUTION = "institution";
    public static final String ATTR_POSITION = "position";
    public static final String ATTR_NATIONAL_ID = "national_id";
    public static final String ATTR_PHONE = "phone";
    public static final String ATTR_STATUS = "ippis_status";
    /** Default join attribute ({@code IPPIS_EMPLOYEE_NUMBER_ATTRIBUTE}). */
    public static final String ATTR_EMPLOYEE_NUMBER = "employee_number";

    /** Attributes that may be CLEARED when IPPIS stops providing them; status never is. */
    public static final List<String> CLEARABLE_ATTRS =
            List.of(ATTR_INSTITUTION, ATTR_POSITION, ATTR_NATIONAL_ID, ATTR_PHONE);

    private IppisAttributeProjection() {
    }

    /** Normalise an upstream institution code to a canonical slug (ADR-0001 §5.2). */
    public static InstitutionResolution resolveInstitutionCode(String raw, InstitutionRegistry registry) {
        if (raw == null || raw.isBlank()) {
            return InstitutionResolution.ABSENT;
        }
        String code = raw.strip();
        Optional<String> slug = registry == null ? Optional.empty() : registry.slugForIppisCode(code);
        return slug.map(s -> new InstitutionResolution(s, null))
                .orElseGet(() -> new InstitutionResolution(null, code));
    }

    /** First value of a single-valued Keycloak attribute; null for missing/empty. */
    public static String firstAttr(Map<String, List<String>> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        List<String> values = attributes.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    /** The attributes one person maps to, null values dropped (insertion-ordered). */
    public static Map<String, String> mappedAttributes(PersonView view, InstitutionRegistry registry) {
        Map<String, String> mapped = new LinkedHashMap<>();
        putIfNotNull(mapped, ATTR_INSTITUTION, resolveInstitutionCode(view.institutionCode(), registry).slug());
        putIfNotNull(mapped, ATTR_POSITION, view.position());
        putIfNotNull(mapped, ATTR_NATIONAL_ID, view.nationalId());
        putIfNotNull(mapped, ATTR_PHONE, view.phone());
        putIfNotNull(mapped, ATTR_STATUS, view.status());
        return mapped;
    }

    /** Subset of {@code mapped} whose value differs from the user's current first value. */
    public static Map<String, String> changed(Map<String, List<String>> userAttributes, Map<String, String> mapped) {
        Map<String, String> out = new LinkedHashMap<>();
        mapped.forEach((k, v) -> {
            if (!Objects.equals(firstAttr(userAttributes, k), v)) {
                out.put(k, v);
            }
        });
        return out;
    }

    /**
     * Managed attributes the user has but IPPIS no longer provides → clear. The institution is
     * protected (never cleared) when IPPIS sent an UNMAPPED code or the person is AMBIGUOUS.
     */
    public static Map<String, String> toClear(Map<String, List<String>> userAttributes, PersonView view,
                                              InstitutionRegistry registry) {
        Map<String, String> mapped = mappedAttributes(view, registry);
        boolean protectedInstitution =
                resolveInstitutionCode(view.institutionCode(), registry).isUnmapped() || view.isAmbiguous();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : CLEARABLE_ATTRS) {
            if (!mapped.containsKey(k)
                    && firstAttr(userAttributes, k) != null
                    && !(k.equals(ATTR_INSTITUTION) && protectedInstitution)) {
                out.put(k, null);
            }
        }
        return out;
    }

    /** The full write for one record ({@code apply_attributes} minus the I/O). */
    public static AttributeSyncPlan plan(Map<String, List<String>> userAttributes, PersonView view,
                                         InstitutionRegistry registry, boolean clearMissing) {
        InstitutionResolution resolution = resolveInstitutionCode(view.institutionCode(), registry);
        Map<String, String> write = new LinkedHashMap<>(changed(userAttributes, mappedAttributes(view, registry)));
        if (clearMissing) {
            write.putAll(toClear(userAttributes, view, registry));
        }
        String before = firstAttr(userAttributes, ATTR_INSTITUTION);
        before = before != null && !before.isBlank() ? before.strip().toLowerCase(Locale.ROOT) : null;
        String after = write.containsKey(ATTR_INSTITUTION) ? write.get(ATTR_INSTITUTION) : before;
        return new AttributeSyncPlan(
                write,
                resolution.unmappedCode(),
                view.isAmbiguous() ? view.employeeNumber() : null,
                before,
                after);
    }

    private static void putIfNotNull(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
