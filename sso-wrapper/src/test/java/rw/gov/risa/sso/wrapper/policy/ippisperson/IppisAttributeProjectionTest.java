package rw.gov.risa.sso.wrapper.policy.ippisperson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;

/** Pure-logic port of src/tests/test_ippis_sync.py (enrich/apply_attributes/normalisation). */
class IppisAttributeProjectionTest {

    static final InstitutionRegistry REGISTRY = InstitutionRegistry.fromRaw(Map.of("institutions", List.of(
            Map.of("slug", "mineduc", "ippis_codes", List.of("MINEDUC")),
            Map.of("slug", "minisante", "ippis_codes", List.of("MINISANTE", "MOH")))), "<test>");

    static PersonView flat(String institution, String position, String nationalId, EmploymentStatus status) {
        return IppisPersonViews.viewOfEmployee(new IppisEmployeeRecord("EMP001", nationalId, "Jean", "Doe",
                "jean@gov.rw", null, institution, position, status, null));
    }

    static PersonView emp001() {
        return flat("MINEDUC", "Accountant", "1199000000000001", EmploymentStatus.ACTIVE);
    }

    static Map<String, List<String>> attrs(Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], List.of((String) kv[i + 1]));
        }
        return m;
    }

    @Test
    void writesMappedAttributesNormalisedThroughTheRegistry() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("employee_number", "EMP001"), emp001(), REGISTRY, false);
        assertThat(plan.isUnchanged()).isFalse();
        assertThat(plan.write()).containsExactly(
                Map.entry("institution", "mineduc"),
                Map.entry("position", "Accountant"),
                Map.entry("national_id", "1199000000000001"),
                Map.entry("ippis_status", "active"));
        assertThat(plan.unmappedInstitution()).isNull();
    }

    @Test
    void neverWritesEmailNorEmployeeNumber() {
        Map<String, String> mapped = IppisAttributeProjection.mappedAttributes(emp001(), REGISTRY);
        assertThat(mapped).doesNotContainKeys("email", "employee_number", "first_name", "firstName");
    }

    @Test
    void phoneIsSourced() {
        PersonView v = IppisPersonViews.viewOfEmployee(new IppisEmployeeRecord("E", null, null, null, null,
                "0788000000", null, null, null, null));
        assertThat(IppisAttributeProjection.mappedAttributes(v, REGISTRY))
                .containsEntry("phone", "0788000000").doesNotContainKey("institution");
    }

    @Test
    void isNoopWhenAttributesAlreadyMatch() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs(
                "institution", "mineduc", "position", "Accountant",
                "national_id", "1199000000000001", "ippis_status", "active"), emp001(), REGISTRY, true);
        assertThat(plan.isUnchanged()).isTrue();
        assertThat(plan.changedKeys()).isEmpty();
    }

    @Test
    void clearsMissingWhenEnabled() {
        PersonView v = flat(null, null, null, EmploymentStatus.ACTIVE);
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("institution", "MINEDUC", "ippis_status", "active"),
                v, REGISTRY, true);
        assertThat(plan.write()).containsOnlyKeys("institution");
        assertThat(plan.write().get("institution")).isNull();
    }

    @Test
    void leavesMissingByDefault() {
        PersonView v = flat(null, null, null, EmploymentStatus.ACTIVE);
        assertThat(IppisAttributeProjection.plan(attrs("institution", "MINEDUC", "ippis_status", "active"),
                v, REGISTRY, false).isUnchanged()).isTrue();
    }

    @Test
    void statusIsNeverACandidateForClearing() {
        assertThat(IppisAttributeProjection.CLEARABLE_ATTRS).doesNotContain("ippis_status");
    }

    @Test
    void normalisesAManyToOneCode() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs(), flat("MOH", null, null, null), REGISTRY, false);
        assertThat(plan.write()).containsEntry("institution", "minisante");
        assertThat(plan.unmappedInstitution()).isNull();
    }

    @Test
    void doesNotWriteAnUnknownCodeButSyncsTheRest() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs(), flat("MINICOM", "Clerk", null, null), REGISTRY, false);
        assertThat(plan.write()).doesNotContainKey("institution").containsEntry("position", "Clerk");
        assertThat(plan.unmappedInstitution()).isEqualTo("MINICOM");
    }

    @Test
    void anUnknownCodeDoesNotClearAnExistingSlug() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("institution", "mineduc", "ippis_status", "active"),
                flat("MINICOM", null, null, null), REGISTRY, true);
        assertThat(plan.isUnchanged()).isTrue();
        assertThat(plan.unmappedInstitution()).isEqualTo("MINICOM");
    }

    @Test
    void anAmbiguousPersonsInstitutionIsNeverClearedAndIsReported() {
        PersonView ambiguous = IppisPersonViews.viewOf(IppisPerson.of("EMP7",
                IppisEmployment.of("MINEDUC", IppisJob.of("a", "x", java.time.LocalDate.parse("2020-01-01"), null, false)),
                IppisEmployment.of("MOH", IppisJob.of("b", "x", java.time.LocalDate.parse("2020-01-01"), null, false))));
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("institution", "mineduc", "position", "old",
                "ippis_status", "active"), ambiguous, REGISTRY, true);
        assertThat(plan.write()).doesNotContainKey("institution");
        assertThat(plan.write()).containsKey("position"); // position is null now -> cleared
        assertThat(plan.write().get("position")).isNull();
        assertThat(plan.ambiguousInstitution()).isEqualTo("EMP7");
    }

    @Test
    void anEmptyRegistryLeavesInstitutionUntouchedAndReportsTheCode() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs(), flat("MINEDUC", "Clerk", null, null),
                InstitutionRegistry.empty("<missing>"), false);
        assertThat(plan.write()).doesNotContainKey("institution").containsEntry("position", "Clerk");
        assertThat(plan.unmappedInstitution()).isEqualTo("MINEDUC");
        assertThat(IppisAttributeProjection.resolveInstitutionCode("MINEDUC", null).unmappedCode()).isEqualTo("MINEDUC");
    }

    @Test
    void resolveInstitutionCodeThreeStates() {
        assertThat(IppisAttributeProjection.resolveInstitutionCode(null, REGISTRY)).isEqualTo(InstitutionResolution.ABSENT);
        assertThat(IppisAttributeProjection.resolveInstitutionCode("  ", REGISTRY)).isEqualTo(InstitutionResolution.ABSENT);
        assertThat(IppisAttributeProjection.resolveInstitutionCode(" moh ", REGISTRY))
                .isEqualTo(new InstitutionResolution("minisante", null));
        InstitutionResolution r = IppisAttributeProjection.resolveInstitutionCode(" MINICOM ", REGISTRY);
        assertThat(r).isEqualTo(new InstitutionResolution(null, "MINICOM"));
        assertThat(r.isUnmapped()).isTrue();
        assertThat(r.isMapped()).isFalse();
    }

    @Test
    void auditExtraRecordsAMoveBetweenMinistries() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("institution", "MINEDUC"),
                flat("MOH", null, null, null), REGISTRY, false);
        assertThat(plan.institutionBefore()).isEqualTo("mineduc");
        assertThat(plan.institutionAfter()).isEqualTo("minisante");
        assertThat(plan.auditExtra("u1")).containsEntry("target_user", "u1")
                .containsEntry("institution", "minisante").containsEntry("institution_before", "mineduc")
                .containsEntry("attributes", List.of("institution", "ippis_status"));
    }

    @Test
    void auditExtraOmitsBeforeWhenNotAMove() {
        AttributeSyncPlan plan = IppisAttributeProjection.plan(attrs("institution", "mineduc"),
                flat("MINEDUC", "Clerk", null, null), REGISTRY, false);
        assertThat(plan.institutionAfter()).isEqualTo("mineduc");
        assertThat(plan.auditExtra("u1")).doesNotContainKey("institution_before").containsEntry("institution", "mineduc");
    }

    @Test
    void firstAttrReadsKeycloakShape() {
        assertThat(IppisAttributeProjection.firstAttr(null, "x")).isNull();
        assertThat(IppisAttributeProjection.firstAttr(Map.of("x", List.of()), "x")).isNull();
        assertThat(IppisAttributeProjection.firstAttr(Map.of("x", List.of("a", "b")), "x")).isEqualTo("a");
    }

    @Test
    void changedComparesFirstValues() {
        assertThat(IppisAttributeProjection.changed(attrs("a", "1"), Map.of("a", "1", "b", "2")))
                .containsExactly(Map.entry("b", "2"));
    }
}
