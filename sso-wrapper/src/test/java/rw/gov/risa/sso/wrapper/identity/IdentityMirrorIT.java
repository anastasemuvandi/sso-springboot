package rw.gov.risa.sso.wrapper.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.at;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.closed;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.open;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.person;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.ippis.IdentityMirrorWriter;
import rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures;
import rw.gov.risa.sso.wrapper.service.ippis.IppisRegistryProvider;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** Postgres 16: Flyway, the append-only trigger, the writer's refusals and the directory's scoping. */
@Tag("it")
@Testcontainers
@WrapperTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityMirrorIT {

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

    @BeforeEach
    void registry() {
        registry.use(IppisFixtures.registry());
    }

    private String record(IdentityMirrorWriter.Recorder rec, IppisPerson p, String kcUser) {
        return rec.record(p, ReconcileOutcome.of(kcUser, kcUser == null ? IppisOutcome.NO_USER : IppisOutcome.UPDATED,
                null), kcUser, kcUser == null ? MatchState.UNMATCHED : MatchState.MATCHED);
    }

    @Test
    @Order(1)
    void migrationsApplyAndTheWriterRecordsTheFiveTables() {
        Integer applied = store.jdbc().getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM flyway_identity_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);

        IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
        assertThat(record(rec, person("EMP1", "N1", at("MINAGRI", open("j1", "2020-01-01", "Agronomist"))), "kc-1"))
                .isNull();
        // dual appointee: one person, two employments
        assertThat(record(rec, person("EMP2", "N2", at("MINAGRI", open("j2a", "2018-01-01", "Vet")),
                at("MINISANTE", new rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob("j2b", null, "Board", null,
                        null, null, false, "Board Member", true, false, java.time.LocalDate.parse("2022-01-01"), null,
                        "New", null, null))), null)).isNull();
        assertThat(record(rec, person("EMP3", "N3", at("MINISANTE", open("j3", "2020-01-01", "Nurse"))), null)).isNull();
        assertThat(record(rec, person("EMP4", "N4", at("MOH", open("j4", "2020-01-01", "Coord"))), null)).isNull();
        assertThat(rec.errors()).isZero();
        assertThat(store.employees().count()).isEqualTo(4);
        assertThat(store.employments().count()).isEqualTo(5);
        String slug = store.jdbc().getJdbcTemplate().queryForObject(
                "SELECT short_code FROM institution WHERE ippis_institution_id = 'MINAGRI'", String.class);
        assertThat(slug).isEqualTo("minagri");
        String group = store.jdbc().getJdbcTemplate().queryForObject(
                "SELECT employee_group FROM job WHERE ippis_job_id = 'j2b'", String.class);
        assertThat(group).isEqualTo("board_member");
    }

    @Test
    @Order(2)
    void jobIsAppendOnlyEnforcedByTheTrigger() {
        var jdbc = store.jdbc().getJdbcTemplate();
        assertThatThrownBy(() -> jdbc.update("UPDATE job SET position_name = 'Rewritten' WHERE ippis_job_id = 'j1'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM job WHERE ippis_job_id = 'j1'"))
                .hasMessageContaining("append-only");
        // closing a job IS allowed, and the writer does it through the same rule
        IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
        assertThat(record(rec, person("EMP1", "N1", at("MINAGRI", closed("j1", "2020-01-01", "2026-06-30",
                "Agronomist"))), "kc-1")).isNull();
        assertThat(rec.summary().get("jobs_closed")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT end_date::text FROM job WHERE ippis_job_id = 'j1'", String.class))
                .isEqualTo("2026-06-30");
        // reopen for the directory tests below
        assertThat(record(rec, person("EMP1", "N1", at("MINAGRI", open("j1", "2020-01-01", "Agronomist"))), "kc-1"))
                .isNull();
    }

    @Test
    @Order(3)
    void reassignedEmployeeNumberIsRefusedAndCountedNeverWritten() {
        IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
        String err = record(rec, person("EMP1", "SOMEONE-ELSE", at("MINEDUC", open("jx", "2026-01-01", "Imposter"))),
                "kc-9");
        assertThat(err).contains("refusing to overwrite");
        assertThat(rec.reassignments()).isEqualTo(1);
        assertThat(rec.errors()).isZero();
        assertThat(store.employees().findById("EMP1").orElseThrow().getNationalId()).isEqualTo("N1");
        assertThat(store.jdbc().getJdbcTemplate().queryForObject("SELECT count(*) FROM job WHERE ippis_job_id = 'jx'",
                Integer.class)).isZero();
        // identifier conflict is a refusal too (EMP5 claims EMP3's national id)
        assertThat(record(rec, person("EMP5", "N3", at("MINAGRI", open("j5", "2026-01-01", "X"))), null))
                .contains("already holds");
        assertThat(rec.conflicts()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void shortCodeIsInsertOnly() {
        var jdbc = store.jdbc().getJdbcTemplate();
        jdbc.update("UPDATE institution SET short_code = 'hand-edited', is_onboarded = true "
                + "WHERE ippis_institution_id = 'MOH'");
        writer.recorder().orElseThrow().record(person("EMP4", "N4", at("MOH", open("j4", "2020-01-01", "Coord"))),
                null, null, null);
        assertThat(jdbc.queryForMap("SELECT short_code, is_onboarded FROM institution WHERE ippis_institution_id = 'MOH'"))
                .isEqualTo(Map.of("short_code", "hand-edited", "is_onboarded", true));
        jdbc.update("UPDATE institution SET short_code = NULL, is_onboarded = false WHERE ippis_institution_id = 'MOH'");
    }

    @Test
    @Order(5)
    void directoryIsOneRowPerEmploymentWithNoEmailSummaryAndNoNationalId() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(false))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.summary.unmapped").value(1))
                .andExpect(jsonPath("$.summary.linked").value(1))
                .andExpect(jsonPath("$.summary.no_email").exists())
                .andExpect(jsonPath("$.items[?(@.employee_number == 'EMP2')]").isArray())
                .andExpect(jsonPath("$.items[?(@.employee_number == 'EMP2')].institution_slug")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("minagri", "minisante")))
                .andExpect(jsonPath("$.items[0].national_id").doesNotExist())
                .andExpect(jsonPath("$.items[0].nationalId").doesNotExist());
    }

    @Test
    @Order(6)
    void scopedAdminSeesOnlyTheirInstitutionAndNoParameterWidensIt() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(true))
                .andExpect(jsonPath("$.institution").value("minagri"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.summary.unmapped").value(0));
        mvc.perform(get("/employees?institution=minisante").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/employees?unmapped=true").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @Order(7)
    void detailIs404NotForbiddenForAnotherInstitutionAndFiltersEmployments() throws Exception {
        mvc.perform(get("/employees/EMP3").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/employees/NOPE").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/employees/EMP2").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(true))
                .andExpect(jsonPath("$.nationalId").value("N2"))
                .andExpect(jsonPath("$.employments.length()").value(1))
                .andExpect(jsonPath("$.employments[0].institutionSlug").value("minagri"));
        mvc.perform(get("/employees/EMP2").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoped").value(false))
                .andExpect(jsonPath("$.employments.length()").value(2))
                .andExpect(jsonPath("$.employments[1].jobs[0].isSecondaryEmployment").value(true));
    }

    @Test
    @Order(8)
    void devSeederCoversEveryStateAndIsIdempotent() {
        // roster through the real writer, twice
        var roster = rw.gov.risa.sso.wrapper.service.ippis.DevRoster.roster();
        for (int i = 0; i < 2; i++) {
            IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
            for (IppisPerson p : roster) {
                record(rec, p, null);
            }
            assertThat(rec.errors()).isZero();
            assertThat(rec.reassignments() + rec.conflicts()).isZero();
        }
        List<String> leaver = store.jdbc().getJdbcTemplate().queryForList(
                "SELECT end_date::text FROM job WHERE ippis_job_id = 'j2008'", String.class);
        assertThat(leaver).containsExactly("2026-06-30");
    }
}
