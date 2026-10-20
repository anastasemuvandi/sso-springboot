package rw.gov.risa.sso.wrapper.policy.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployment;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews;
import rw.gov.risa.sso.wrapper.policy.ippisperson.PersonView;
import rw.gov.risa.sso.wrapper.policy.lifecycle.RehirePolicy.Decision;

class RehirePolicyTest {

    static final PersonView REHIRED = IppisPersonViews.viewOf(IppisPerson.of("E1",
            IppisEmployment.of("MINAGRI", IppisJob.of("old", "Clerk", LocalDate.parse("2015-01-01"), LocalDate.parse("2020-01-01"), false)),
            IppisEmployment.of("MINEDUC", IppisJob.of("new", "Teacher", LocalDate.parse("2026-09-01"), null, false))));
    static final PersonView LEAVER = IppisPersonViews.viewOf(IppisPerson.of("E1",
            IppisEmployment.of("MINAGRI", IppisJob.of("old", "Clerk", LocalDate.parse("2015-01-01"), LocalDate.parse("2020-01-01"), false))));
    static final PersonView EMPTY = IppisPersonViews.viewOf(IppisPerson.of("E1"));

    static Map<String, List<String>> by(String trigger) {
        return Map.of("offboarded_by", List.of(trigger), "offboarded_at", List.of("2026-01-01T00:00:00Z"));
    }

    @Test
    void reEnablesAFeedOffboardedAccountWithAnOpenPost() {
        assertThat(RehirePolicy.decide(false, REHIRED, by("ippis-leaver-feed"))).isEqualTo(Decision.REENABLE);
        assertThat(RehirePolicy.decide(false, REHIRED, by("ippis-reconcile"))).isEqualTo(Decision.REENABLE);
    }

    @Test
    void neverReversesAManualOffboarding() {
        assertThat(RehirePolicy.decide(false, REHIRED, by("admin@gov.rw"))).isEqualTo(Decision.MANUAL_DISABLE_KEPT);
        assertThat(RehirePolicy.decide(false, REHIRED, Map.of())).isEqualTo(Decision.MANUAL_DISABLE_KEPT);
        assertThat(RehirePolicy.decide(false, REHIRED, null)).isEqualTo(Decision.MANUAL_DISABLE_KEPT);
    }

    @Test
    void stillALeaverOrUnreadableStaysDisabled() {
        assertThat(RehirePolicy.decide(false, LEAVER, by("ippis-leaver-feed"))).isEqualTo(Decision.STILL_LEAVER);
        assertThat(RehirePolicy.decide(false, EMPTY, by("ippis-leaver-feed"))).isEqualTo(Decision.NO_EMPLOYMENT);
    }

    @Test
    void enabledAccountsAreNotTouched() {
        assertThat(RehirePolicy.decide(true, REHIRED, by("ippis-leaver-feed"))).isEqualTo(Decision.NOT_DISABLED);
        assertThat(RehirePolicy.decide(null, REHIRED, by("ippis-leaver-feed"))).isEqualTo(Decision.NOT_DISABLED);
    }

    @Test
    void reEnableClearsMarkersAndIsAudited() {
        assertThat(RehirePolicy.clearMarkers()).containsKeys("offboarded_by", "offboarded_at").containsValue(null);
        assertThat(RehirePolicy.auditExtra("u1", "jean", REHIRED, by("ippis-reconcile")))
                .containsEntry("target_user", "u1").containsEntry("employee_number", "E1")
                .containsEntry("offboarded_by", "ippis-reconcile").containsEntry("offboarded_at", "2026-01-01T00:00:00Z");
        assertThat(RehirePolicy.AUDIT_EVENT).isEqualTo("lifecycle.rehire");
        assertThat(Decision.MANUAL_DISABLE_KEPT.wire()).isEqualTo("manual_disable_kept");
    }

    @Test
    void offboardingMarkerFromTheFeedIsWhatTheRehireRuleReads() {
        OffboardingPlan p = OffboardingPlanner.plan(new OffboardingInput("u", "n", true, 0, null, null, null, null),
                "r", "ippis-leaver-feed", java.time.Instant.EPOCH);
        assertThat(RehirePolicy.decide(false, REHIRED, p.markerAttributes())).isEqualTo(Decision.REENABLE);
    }
}
