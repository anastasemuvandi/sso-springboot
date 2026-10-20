package rw.gov.risa.sso.wrapper.isolation;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.at;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.closed;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.open;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.person;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.ippis.IdentityMirrorWriter;
import rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures;
import rw.gov.risa.sso.wrapper.service.ippis.IppisRegistryProvider;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * SPEC §7.1 — the employee directory ({@code /employees}, the IPPIS mirror) is a second read surface over
 * the same boundary: a delegated admin reads only their institution's employments, no parameter widens
 * or drops the institution term, another institution's employee is 404 (never 403, which would confirm
 * the number exists), {@code nationalId} only on the detail, and only a platform admin reads across.
 * Real Postgres 16 (Flyway schema) seeded through the real mirror writer.
 */
@Tag("it")
@Testcontainers
@WrapperTest
class EmployeeDirectoryScopingIsolationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sso_identity").withUsername("sso_identity_rw").withPassword("pw");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sso.identity.database-url", PG::getJdbcUrl);
        r.add("sso.identity.username", PG::getUsername);
        r.add("sso.identity.password", PG::getPassword);
    }

    @Autowired IdentityStore store;
    @Autowired IdentityMirrorWriter writer;
    @Autowired IppisRegistryProvider registry;
    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory keycloak;

    private static volatile boolean seeded;

    @BeforeEach
    void seed() {
        registry.use(IppisFixtures.registry());
        if (seeded) {
            return;
        }
        IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
        record(rec, person("A101", "1199880000000001", at("MINAGRI", open("ja1", "2020-01-01", "Agronomist"))), "kc-a1");
        record(rec, person("A202", "1199880000000002", at("MINAGRI", closed("ja2", "2019-01-01", "2025-01-01", "Vet"))),
                null);
        record(rec, person("B303", "1199880000000003", at("MINISANTE", open("jb1", "2020-01-01", "Nurse"))), "kc-b1");
        // dual appointee: one person, an employment in each institution
        record(rec, person("D404", "1199880000000004", at("MINAGRI", open("jd1", "2018-01-01", "Economist")),
                at("MINISANTE", open("jd2", "2021-01-01", "Advisor"))), null);
        // an employer the registry does not know: nobody scoped may reach it
        record(rec, person("U505", "1199880000000005", at("UNKNOWNCO", open("ju1", "2020-01-01", "Clerk"))), null);
        if (rec.errors() != 0 || rec.conflicts() != 0 || rec.reassignments() != 0) {
            throw new IllegalStateException("seed failed: " + rec.summary());
        }
        seeded = true;
    }

    private static void record(IdentityMirrorWriter.Recorder rec, IppisPerson p, String kcUser) {
        String refused = rec.record(p, ReconcileOutcome.of(kcUser, kcUser == null ? IppisOutcome.NO_USER : IppisOutcome.UPDATED, null),
                kcUser, kcUser == null ? MatchState.UNMATCHED : MatchState.MATCHED);
        if (refused != null) {
            throw new IllegalStateException("seed refused " + p.employeeNumber() + ": " + refused);
        }
    }

    // ---- the list ----------------------------------------------------------------------------

    @Test
    void aLocalAdminReadsOnlyTheirInstitutionsEmployments() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(true))
                .andExpect(jsonPath("$.institution").value("minagri"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].institution_slug", everyItem(is("minagri"))))
                .andExpect(jsonPath("$.items[*].employee_number", not(hasItem("B303"))));
        mvc.perform(get("/employees").with(TestCallers.institutionAdmin("minisante", "auditor")))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].institution_slug", everyItem(is("minisante"))));
    }

    @Test
    void theDualAppointeeIsTwoRowsNeitherOfWhichLeaks() throws Exception {
        mvc.perform(get("/employees").param("search", "D404").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].institution_slug").value("minagri"));
        mvc.perform(get("/employees/D404").with(TestCallers.institutionAdmin("minisante", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employments.length()").value(1))
                .andExpect(jsonPath("$.employments[0].institutionSlug").value("minisante"));
    }

    @Test
    void namingAnotherInstitutionIsRefusedNotHonoured() throws Exception {
        mvc.perform(get("/employees").param("institution", "minisante").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/employees/B303").param("institution", "minisante")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"search=B303", "search=Nurse", "unmapped=true", "status=active", "account=linked",
            "sort=institution", "search=%25", "search=minisante"})
    void noCallerSuppliedFilterDropsTheInstitutionTerm(String query) throws Exception {
        mvc.perform(get("/employees?" + query).with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].employee_number", not(hasItem("B303"))))
                .andExpect(jsonPath("$.items[*].employee_number", not(hasItem("U505"))))
                .andExpect(jsonPath("$.items[*].institution_slug", everyItem(is("minagri"))));
    }

    @Test
    void aRecordWithAnUnresolvedInstitutionReachesNobodyScoped() throws Exception {
        mvc.perform(get("/employees?unmapped=true").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/employees/U505").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/employees?unmapped=true").with(TestCallers.superAdmin()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void theListNeverCarriesTheNationalId() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(jsonPath("$.items[0].national_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].nationalId").doesNotExist());
    }

    // ---- the detail --------------------------------------------------------------------------

    @Test
    void anotherInstitutionsEmployeeIs404NeverForbiddenAndLooksLikeANonExistentOne() throws Exception {
        mvc.perform(get("/employees/B303").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/employees/NOPE-404").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/employees/A101").with(TestCallers.institutionAdmin("minisante", "admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void myEmployeesDetailCarriesTheNationalId() throws Exception {
        mvc.perform(get("/employees/A101").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value("1199880000000001"));
    }

    // ---- who may read at all -----------------------------------------------------------------

    @Test
    void onlyAPlatformAdminReadsAcrossInstitutions() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(false))
                .andExpect(jsonPath("$.total").value(6));
        mvc.perform(get("/employees").param("institution", "minisante").with(TestCallers.superAdmin()))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/employees/B303").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void aCallerWithNoGrantReadsNothing() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/employees/A101").with(TestCallers.plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBroadAdminRoleAlongsideAGrantIsRefused() throws Exception {
        var caller = TestCallers.caller("minagri.admin", Set.of("inst:minagri:admin"),
                Map.of("realm-management", Set.of("view-users")), "minagri");
        mvc.perform(get("/employees").with(TestCallers.as(caller))).andExpect(status().isForbidden());
    }

    @Test
    void theStoreReallyHoldsOtherInstitutionsRows() {
        // guards the assertions above against an empty seed
        Integer b = store.jdbc().getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM employment em JOIN institution i ON em.institution_id = i.id "
                        + "WHERE i.short_code = 'minisante'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(b).isEqualTo(2);
    }
}
