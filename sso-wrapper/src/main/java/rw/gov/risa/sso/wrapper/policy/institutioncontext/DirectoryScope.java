package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.List;

/**
 * Which institutions a caller may READ a directory listing of. {@code institution == null}
 * means every institution and only ever happens for a platform admin; otherwise it is a slug
 * the caller was proven to administer (never the raw query parameter).
 */
public record DirectoryScope(String actor, String userId, String institution, List<String> administered,
                             boolean platformAdmin) {

    public DirectoryScope {
        administered = administered == null ? List.of() : List.copyOf(administered);
    }

    public boolean isScoped() {
        return institution != null;
    }
}
