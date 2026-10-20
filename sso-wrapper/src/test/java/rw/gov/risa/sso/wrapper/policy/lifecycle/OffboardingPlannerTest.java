package rw.gov.risa.sso.wrapper.policy.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OffboardingPlannerTest {

    static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    static OffboardingInput input() {
        return new OffboardingInput("u1", "jean@gov.rw", true, 3,
                List.of(new GroupRef("g1", "minagri"), new GroupRef("g2", "planning")),
                List.of("default-roles-government-internal", "inst:minagri:admin", "inst:minagri:viewer",
                        "offline_access", "inst:rdb:viewer", "inst:minagri:admin"),
                List.of("minagri-kubaka-web", "rdb-portal", "minagri-kubaka-web"),
                Map.of("institution", List.of("MINAGRI")));
    }

    @Test
    void stripsInstitutionRolesNamedGrantsAndGroups() {
        OffboardingPlan p = OffboardingPlanner.plan(input(), "IPPIS reports no current employment for E1",
                "ippis-leaver-feed", NOW);
        assertThat(p.disable()).isTrue();
        assertThat(p.sessionsToKill()).isEqualTo(3);
        assertThat(p.groupsToRemove()).extracting(GroupRef::id).containsExactly("g1", "g2");
        assertThat(p.rolesToStrip()).containsExactly("inst:minagri:admin", "inst:minagri:viewer", "inst:rdb:viewer");
        assertThat(p.clientsToUnname()).containsExactly("minagri-kubaka-web", "rdb-portal");
        assertThat(p.institution()).isEqualTo("minagri");
        assertThat(p.markerAttributes()).containsEntry("offboarded_by", List.of("ippis-leaver-feed"))
                .containsEntry("offboarded_at", List.of("2026-10-20T10:00:00Z"));
    }

    @Test
    void nonInstitutionRolesAreKept() {
        assertThat(OffboardingPlanner.isInstitutionRole("inst:x:y")).isTrue();
        assertThat(OffboardingPlanner.isInstitutionRole("institution-admin")).isFalse();
        assertThat(OffboardingPlanner.isInstitutionRole("super_admin")).isFalse();
        assertThat(OffboardingPlanner.isInstitutionRole(null)).isFalse();
    }

    @Test
    void auditExtraNamesWhatWasRemoved() {
        Map<String, Object> extra = OffboardingPlanner.plan(input(), "r", "admin-1", NOW).auditExtra();
        assertThat(extra).containsEntry("user_id", "u1").containsEntry("target_user", "u1")
                .containsEntry("target_username", "jean@gov.rw").containsEntry("institution", "minagri")
                .containsEntry("reason", "r").containsEntry("sessions_killed", 3)
                .containsEntry("groups_removed", List.of("minagri", "planning"))
                .containsEntry("roles_removed", List.of("inst:minagri:admin", "inst:minagri:viewer", "inst:rdb:viewer"))
                .containsEntry("client_grants_removed", List.of("minagri-kubaka-web", "rdb-portal"));
    }

    @Test
    void untaggedUserHasNoInstitutionKey() {
        OffboardingInput in = new OffboardingInput("u", "n", true, 0, null, null, null, null);
        OffboardingPlan p = OffboardingPlanner.plan(in, "r", "x", NOW);
        assertThat(p.auditExtra()).doesNotContainKey("institution");
        assertThat(p.rolesToStrip()).isEmpty();
        assertThat(p.failureAuditExtra("boom")).containsEntry("rolled_back", true).containsEntry("error", "boom")
                .doesNotContainKey("institution");
    }

    @Test
    void failureErrorIsTruncatedTo300() {
        OffboardingPlan p = OffboardingPlanner.plan(input(), "r", "x", NOW);
        assertThat((String) p.failureAuditExtra("e".repeat(500)).get("error")).hasSize(300);
        assertThat(p.failureAuditExtra(null)).containsEntry("error", "");
    }

    @Test
    void rollbackReAddsWhatWasRemovedButNeverSessions() {
        OffboardingPlanner.Progress progress = new OffboardingPlanner.Progress(true,
                List.of(new GroupRef("g1", "minagri")), List.of("inst:minagri:admin"), List.of());
        OffboardingPlanner.Rollback rb = OffboardingPlanner.rollback(progress);
        assertThat(rb.reEnable()).isTrue();
        assertThat(rb.groupsToReAdd()).extracting(GroupRef::id).containsExactly("g1");
        assertThat(rb.rolesToReAdd()).containsExactly("inst:minagri:admin");
        assertThat(rb.isEmpty()).isFalse();
        assertThat(OffboardingPlanner.rollback(OffboardingPlanner.Progress.none()).isEmpty()).isTrue();
    }

    @Test
    void stepOrder() {
        assertThat(OffboardingStep.values()).containsExactly(OffboardingStep.KILL_SESSIONS, OffboardingStep.DISABLE_ACCOUNT,
                OffboardingStep.REMOVE_GROUPS, OffboardingStep.STRIP_INSTITUTION_ROLES, OffboardingStep.REMOVE_NAMED_GRANTS);
    }

    @Test
    void groupRefNullNameIsEmpty() {
        assertThat(new GroupRef("g", null).name()).isEmpty();
    }
}
