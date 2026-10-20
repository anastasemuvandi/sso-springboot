package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/** One leaver still at risk. {@code institution} is set on the platform overview only. */
public record LeaverRow(
        String employeeNumber,
        String name,
        String institution,
        String sub,
        String username,
        boolean enabled,
        List<String> applications,
        List<LeaverReason> reasons) {

    public LeaverRow {
        applications = List.copyOf(applications);
        reasons = List.copyOf(reasons);
    }
}
