package rw.gov.risa.sso.wrapper.policy.ippisperson;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy.Dispatch;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy.LeaverDecision;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy.MatchState;

/** Ports of the decision parts of test_ippis_leaver.py / test_ippis_reconcile.py. */
class IppisReconcilePolicyTest {

    static PersonView view(EmploymentStatus status) {
        return IppisPersonViews.viewOfEmployee(new IppisEmployeeRecord("EMP404", null, null, null, null, null,
                "MINEDUC", "Clerk", status, null));
    }

    @Test
    void userWithoutEmployeeNumberIsSkipped() {
        assertThat(IppisReconcilePolicy.employeeNumberOf(Map.of(), "employee_number")).isEmpty();
        assertThat(IppisReconcilePolicy.employeeNumberOf(Map.of("employee_number", List.of("")), null)).isEmpty();
        assertThat(IppisReconcilePolicy.employeeNumberOf(Map.of("employee_number", List.of("EMP1")), null)).contains("EMP1");
        assertThat(IppisReconcilePolicy.employeeNumberOf(Map.of("ippis_no", List.of("X")), "ippis_no")).contains("X");
        ReconcileOutcome o = IppisReconcilePolicy.skipped("u1", "employee_number");
        assertThat(o.status()).isEqualTo(IppisOutcome.SKIPPED);
        assertThat(o.detail()).isEqualTo("no employee_number");
    }

    @Test
    void notFoundAndNoUserDetails() {
        assertThat(IppisReconcilePolicy.notFound("u", "EMP9").detail()).isEqualTo("employee_number EMP9 not in IPPIS");
        ReconcileOutcome n = IppisReconcilePolicy.noUser("employee_number", "EMP9");
        assertThat(n.status().wire()).isEqualTo("no_user");
        assertThat(n.userId()).isNull();
        assertThat(n.detail()).isEqualTo("no Keycloak user with employee_number=EMP9");
    }

    @Test
    void leaversAreOffboardedOthersEnriched() {
        assertThat(IppisReconcilePolicy.dispatch(view(EmploymentStatus.TERMINATED))).isEqualTo(Dispatch.OFFBOARD_IF_ENABLED);
        assertThat(IppisReconcilePolicy.dispatch(view(EmploymentStatus.ACTIVE))).isEqualTo(Dispatch.ENRICH);
        assertThat(IppisReconcilePolicy.dispatch(view(EmploymentStatus.SUSPENDED))).isEqualTo(Dispatch.ENRICH);
    }

    @Test
    void leaverDecision() {
        assertThat(IppisReconcilePolicy.leaverDecision(true, view(EmploymentStatus.ACTIVE))).isEqualTo(LeaverDecision.ACTIVE);
        assertThat(IppisReconcilePolicy.leaverDecision(false, view(EmploymentStatus.ACTIVE))).isEqualTo(LeaverDecision.ACTIVE);
        assertThat(IppisReconcilePolicy.leaverDecision(false, view(EmploymentStatus.TERMINATED)))
                .isEqualTo(LeaverDecision.ALREADY_DISABLED);
        assertThat(IppisReconcilePolicy.leaverDecision(true, view(EmploymentStatus.TERMINATED))).isEqualTo(LeaverDecision.DISABLE);
        // enabled unknown (absent) is not "disabled": the account is offboarded.
        assertThat(IppisReconcilePolicy.leaverDecision(null, view(EmploymentStatus.TERMINATED))).isEqualTo(LeaverDecision.DISABLE);
        assertThat(IppisReconcilePolicy.leaverReason(view(EmploymentStatus.TERMINATED)))
                .isEqualTo("IPPIS reports no current employment for EMP404");
    }

    @Test
    void matchState() {
        assertThat(IppisReconcilePolicy.matchState(0)).isEqualTo(MatchState.UNMATCHED);
        assertThat(IppisReconcilePolicy.matchState(1)).isEqualTo(MatchState.MATCHED);
        assertThat(IppisReconcilePolicy.matchState(2)).isEqualTo(MatchState.AMBIGUOUS);
    }

    @Test
    void watermarkAdvancesToTheNewestRecordElseKeepsSince() {
        Instant since = Instant.parse("2026-05-01T00:00:00Z");
        assertThat(IppisReconcilePolicy.newWatermark(Arrays.asList(
                Instant.parse("2026-06-01T08:00:00Z"), null, Instant.parse("2026-07-01T08:00:00Z")), since))
                .isEqualTo(Instant.parse("2026-07-01T08:00:00Z"));
        assertThat(IppisReconcilePolicy.newWatermark(List.of(), since)).isEqualTo(since);
        assertThat(IppisReconcilePolicy.newWatermark(List.of(), null)).isNull();
    }

    @Test
    void summaryCountsMixedBatch() {
        List<ReconcileOutcome> outcomes = List.of(
                ReconcileOutcome.of("u1", IppisOutcome.UPDATED, null),
                ReconcileOutcome.of("u4", IppisOutcome.DISABLED, null),
                ReconcileOutcome.of("u9", IppisOutcome.NOT_FOUND, null),
                ReconcileOutcome.of("u0", IppisOutcome.SKIPPED, null));
        ReconcileSummary s = ReconcileSummary.of(outcomes, null);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.count(IppisOutcome.UPDATED)).isEqualTo(1);
        assertThat(s.count(IppisOutcome.DISABLED)).isEqualTo(1);
        assertThat(s.count(IppisOutcome.NOT_FOUND)).isEqualTo(1);
        assertThat(s.count(IppisOutcome.SKIPPED)).isEqualTo(1);
        assertThat(s.count(IppisOutcome.ERROR)).isZero();
        assertThat(s.outcomes()).extracting(ReconcileOutcome::userId).containsExactly("u1", "u4", "u9", "u0");
    }

    @Test
    void unmappedAndAmbiguousAreOrthogonalToStatus() {
        PersonView amb = IppisPersonViews.viewOf(IppisPerson.of("EMP7",
                IppisEmployment.of("A", IppisJob.of("a", "x", java.time.LocalDate.parse("2020-01-01"), null, false)),
                IppisEmployment.of("B", IppisJob.of("b", "x", java.time.LocalDate.parse("2020-01-01"), null, false))));
        ReconcileOutcome joiner = IppisReconcilePolicy.noUser("employee_number", "EMP7")
                .withFlagsFrom(amb, new InstitutionResolution(null, "MINICOM"));
        ReconcileOutcome unchanged = new ReconcileOutcome("u2", IppisOutcome.UNCHANGED, null, "MINICOM", null, false, null)
                .withFlagsFrom(amb, new InstitutionResolution(null, "OTHER"));
        assertThat(unchanged.unmappedInstitution()).isEqualTo("MINICOM"); // set-if-absent
        ReconcileSummary s = ReconcileSummary.of(List.of(joiner, unchanged), null);
        assertThat(s.unmappedInstitution()).isEqualTo(2);
        assertThat(s.ambiguousInstitution()).isEqualTo(2);
        assertThat(s.count(IppisOutcome.NO_USER)).isEqualTo(1);
    }

    @Test
    void mirrorCounters() {
        ReconcileOutcome ok = ReconcileOutcome.of("a", IppisOutcome.UPDATED, null).withMirror(true, null);
        ReconcileOutcome bad = ReconcileOutcome.of("b", IppisOutcome.UPDATED, null).withMirror(false, "refused");
        ReconcileSummary noRecorder = ReconcileSummary.of(List.of(ok, bad), null);
        assertThat(noRecorder.mirrored()).isEqualTo(1);
        assertThat(noRecorder.mirrorErrors()).isEqualTo(1);
        assertThat(noRecorder.mirrorRefused()).isZero();
        ReconcileSummary withRecorder = ReconcileSummary.of(List.of(ok, bad), new ReconcileSummary.MirrorCounts(0, 1, 2));
        assertThat(withRecorder.mirrorErrors()).isZero();
        assertThat(withRecorder.mirrorRefused()).isEqualTo(3);
    }

    @Test
    void outcomeWireStrings() {
        assertThat(Arrays.stream(IppisOutcome.values()).map(IppisOutcome::wire)).containsExactly(
                "updated", "unchanged", "disabled", "already_disabled", "active", "skipped", "not_found", "no_user", "error");
    }
}
