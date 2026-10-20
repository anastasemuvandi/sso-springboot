package rw.gov.risa.sso.ippismock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.ippismock.policy.PersonView;
import rw.gov.risa.sso.ippismock.store.Employment;
import rw.gov.risa.sso.ippismock.store.Job;

class PersonViewTest {

    private static Job job(String id, String start, String end, boolean secondary) {
        return new Job(id, null, "P-" + id, null, null, null, null, null, secondary, null, start, end, null, null, null);
    }

    private static Employment emp(String employer, Job... jobs) {
        return new Employment(employer, null, null, null, List.of(jobs));
    }

    @Test
    void secondaryNeverBeatsPrimary() {
        PersonView v = PersonView.of(List.of(emp("B", job("b", "2010-01-01", null, true)),
                emp("A", job("a", "2020-01-01", null, false))));
        assertThat(v.institution()).isEqualTo("A");
        assertThat(v.position()).isEqualTo("P-a");
    }

    @Test
    void tieOnStartDateIsAmbiguous() {
        PersonView v = PersonView.of(List.of(emp("A", job("a", "2020-01-01", null, false)),
                emp("B", job("b", "2020-01-01", null, false))));
        assertThat(v.institution()).isNull();
        assertThat(v.ambiguousReason()).isEqualTo(PersonView.AMBIGUOUS_MULTIPLE_PRIMARY);
    }

    @Test
    void leaverKeepsMostRecentlyEndedEmployer() {
        PersonView v = PersonView.of(List.of(emp("A", job("a", "2010-01-01", "2015-01-01", false)),
                emp("B", job("b", "2016-01-01", "2026-01-01", false))));
        assertThat(v.status()).isEqualTo(PersonView.TERMINATED);
        assertThat(v.institution()).isEqualTo("B");
    }

    @Test
    void noEmploymentsIsActiveAndInstitutionless() {
        PersonView v = PersonView.of(List.of());
        assertThat(v.status()).isEqualTo(PersonView.ACTIVE);
        assertThat(v.institution()).isNull();
    }
}
