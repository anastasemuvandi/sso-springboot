package rw.gov.risa.sso.wrapper.policy.ippisperson;

/**
 * Result of normalising an IPPIS institution code; at most one field is set.
 * <ul>
 *   <li>{@code (null, null)} — IPPIS provided no institution;</li>
 *   <li>{@code ("mineduc", null)} — resolved through the registry;</li>
 *   <li>{@code (null, "MOH")} — a code the registry does not know: never written, never
 *       treated as absent (so it never clears a correct slug).</li>
 * </ul>
 */
public record InstitutionResolution(String slug, String unmappedCode) {

    public static final InstitutionResolution ABSENT = new InstitutionResolution(null, null);

    public boolean isMapped() {
        return slug != null;
    }

    public boolean isUnmapped() {
        return unmappedCode != null;
    }
}
