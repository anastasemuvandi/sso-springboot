package rw.gov.risa.sso.wrapper.service.directory;

/**
 * What to narrow the directory to. {@code institution} is the RESOLVED scope (null only for a
 * platform admin reading everything), never a raw query parameter.
 */
public record EmployeeFilters(String institution, String search, String status, String account, boolean unmapped) {

    public static EmployeeFilters scope(String institution) {
        return new EmployeeFilters(institution, null, null, null, false);
    }
}
