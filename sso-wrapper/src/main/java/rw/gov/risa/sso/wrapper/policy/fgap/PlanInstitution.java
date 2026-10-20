package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.List;

/**
 * The slice of one {@code configs/institutions.yaml} entry the FGAP plan needs: the slug,
 * its app names (clients {@code <slug>-<app>}) and department names (groups
 * {@code /<slug>/<department>}). Only active, shared-realm entries belong in a plan
 * (see {@link FgapPlanInputs}).
 */
public record PlanInstitution(String slug, List<String> apps, List<String> departments) {

    public PlanInstitution {
        apps = List.copyOf(apps);
        departments = List.copyOf(departments);
    }
}
