package rw.gov.risa.sso.audit.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.audit.web.ApiKeyFilter;

/** The HTTP surface, end to end against Postgres (reference tests/test_audit.py and more). */
class ApiIT extends PostgresIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header(ApiKeyFilter.HEADER, API_KEY);
    }

    private JsonNode postEvent(String json) throws Exception {
        String body = mvc.perform(authed(post("/audit/events")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private static String login(String user, String extra) {
        return """
                {"event_type": "auth.login.success", "user_id": "%s", "app_id": "minihealth",
                 "realm": "government-internal", "performed_by": "%s", "ip_address": "10.0.0.1",
                 "details": {"username": "john.doe@gov.rw"}, "severity": "INFO"%s}
                """.formatted(user, user, extra);
    }

    @Test
    void ingestRequiresTheApiKey() throws Exception {
        mvc.perform(post("/audit/events").contentType(MediaType.APPLICATION_JSON).content(login("x", "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(post("/audit/events").header(ApiKeyFilter.HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(login("x", "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/audit/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/audit/integrity/verify")).andExpect(status().isUnauthorized());
    }

    @Test
    void eventWrittenOnLoginHasTheReferenceShape() throws Exception {
        JsonNode e = postEvent(login("user-001", ""));
        assertThat(e.get("event_type").asText()).isEqualTo("auth.login.success");
        assertThat(e.get("category").asText()).isEqualTo("AUTHENTICATION");
        assertThat(e.get("user_id").asText()).isEqualTo("user-001");
        assertThat(e.get("event_hash").asText()).hasSize(64).isNotEqualTo("0".repeat(64));
        assertThat(e.get("prev_hash").asText()).hasSize(64);
        assertThat(e.get("hash_version").asInt()).isEqualTo(2);
        assertThat(e.get("details").get("username").asText()).isEqualTo("john.doe@gov.rw");
        assertThat(e.has("institution")).isTrue();
        assertThat(e.get("institution").isNull()).isTrue();
        assertThat(e.get("created_at").asText()).endsWith("Z");
        UUID.fromString(e.get("id").asText());
    }

    @Test
    void accessDeniedAndOffboardCategories() throws Exception {
        JsonNode denied = postEvent("""
                {"event_type": "authz.access.denied", "user_id": "user-002", "app_id": "minagri",
                 "details": {"reason": "not in required group"}, "severity": "WARNING"}""");
        assertThat(denied.get("category").asText()).isEqualTo("AUTHORIZATION");
        assertThat(denied.get("severity").asText()).isEqualTo("WARNING");
        JsonNode off = postEvent("""
                {"event_type": "lifecycle.offboard", "user_id": "user-003", "performed_by": "admin-001",
                 "details": {"reason": "resignation", "groups_removed": ["finance-users"]}}""");
        assertThat(off.get("category").asText()).isEqualTo("LIFECYCLE");
        assertThat(off.get("severity").asText()).isEqualTo("INFO");
    }

    @Test
    void institutionAndTargetAreLiftedFromDetailsOnlyWhenAbsent() throws Exception {
        String inst = "inst-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode lifted = postEvent("""
                {"event_type": "admin.user.created", "performed_by": "alice",
                 "details": {"institution": " %s ", "target_user": "bob", "target": "ignored"}}""".formatted(inst));
        assertThat(lifted.get("institution").asText()).isEqualTo(inst);
        assertThat(lifted.get("target").asText()).isEqualTo("bob");

        JsonNode explicit = postEvent("""
                {"event_type": "admin.user.created", "institution": "%s", "target": "carol",
                 "details": {"institution": "other", "target_user": "dave"}}""".formatted(inst));
        assertThat(explicit.get("target").asText()).isEqualTo("carol");

        // The institution filter is an exact match on the column.
        mvc.perform(authed(get("/audit/events")).param("institution", inst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].institution", everyItem(is(inst))))
                .andExpect(jsonPath("$.items[0].target").value("carol")); // newest first
        mvc.perform(authed(get("/audit/events")).param("institution", inst).param("target", "bob"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void validationErrorsAre422WithTheEnvelope() throws Exception {
        mvc.perform(authed(post("/audit/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_type\": \"auth.x\", \"severity\": \"LOUD\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mvc.perform(authed(post("/audit/events")).contentType(MediaType.APPLICATION_JSON).content("{\"user_id\": \"u\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.fields.eventType", notNullValue()));
        mvc.perform(authed(post("/audit/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_type\": \"auth.x\", \"details\": [1]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(authed(get("/audit/events")).param("limit", "0")).andExpect(status().isUnprocessableEntity());
        mvc.perform(authed(get("/audit/events")).param("limit", "1001")).andExpect(status().isUnprocessableEntity());
        mvc.perform(authed(get("/audit/events")).param("from_date", "soon")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void userEventsAndDateFilters() throws Exception {
        String user = "dates-" + UUID.randomUUID();
        postEvent(login(user, ""));
        postEvent(login(user, ""));
        mvc.perform(authed(get("/audit/events/" + user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].user_id").value(user));
        mvc.perform(authed(get("/audit/events/" + user)).param("from_date", "2999-01-01"))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(authed(get("/audit/events")).param("user_id", user).param("to_date", "2000-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void exportCsvAndPdf() throws Exception {
        String user = "csv-" + UUID.randomUUID();
        postEvent(login(user, ""));
        var csv = mvc.perform(authed(get("/audit/export/csv")).param("user_id", user))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        matchesPattern("attachment; filename=\"audit_export_\\d{8}_\\d{6}\\.csv\"")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("id,created_at,event_type").contains("auth.login.success").contains(user);

        byte[] pdf = mvc.perform(authed(get("/audit/export/pdf")).param("category", "AUTHENTICATION").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(100);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        mvc.perform(authed(get("/audit/export/csv")).param("limit", "50001")).andExpect(status().isUnprocessableEntity());
        mvc.perform(authed(get("/audit/reports/compliance")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("compliance_")));
    }

    @Test
    void integrityVerifyEndpoint() throws Exception {
        postEvent(login("verify-user", ""));
        mvc.perform(authed(get("/audit/integrity/verify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.tampered_count").value(0))
                .andExpect(jsonPath("$.first_tampered_id", nullValue()))
                .andExpect(jsonPath("$.tail_matches_head").value(true))
                .andExpect(jsonPath("$.total_checked", notNullValue()));
        mvc.perform(authed(get("/audit/integrity/verify")).param("from_date", "2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.checked_from").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void retentionPoliciesUpsertAuditAndLegalHold() throws Exception {
        // Defaults: one NULL policy of 365 days, listed first.
        mvc.perform(authed(get("/audit/retention/policies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category", nullValue()))
                .andExpect(jsonPath("$[0].retention_days").value(365));

        // Saving the default twice keeps a single default row (the reference grew a new one each time).
        for (int i = 0; i < 2; i++) {
            mvc.perform(authed(post("/audit/retention/policy")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\": null, \"retention_days\": 365, \"legal_hold\": false}"))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retention_policies WHERE category IS NULL", Long.class))
                .isEqualTo(1);

        // UNKNOWN-category rows only exist in this test, so purging them affects nobody else.
        String user = "retain-" + UUID.randomUUID();
        postEvent("{\"event_type\": \"ittest.retention\", \"user_id\": \"" + user + "\"}");

        mvc.perform(authed(post("/audit/retention/policy")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"UNKNOWN\", \"retention_days\": 0, \"legal_hold\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal_hold").value(true))
                .andExpect(jsonPath("$.retention_days").value(0));
        mvc.perform(authed(post("/audit/retention/apply"))).andExpect(status().isOk());
        assertThat(count(user)).as("legal hold keeps the rows").isEqualTo(1);

        mvc.perform(authed(post("/audit/retention/policy")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"UNKNOWN\", \"retention_days\": 0, \"legal_hold\": false}"))
                .andExpect(status().isOk());
        String applied = mvc.perform(authed(post("/audit/retention/apply")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode r = mapper.readTree(applied);
        assertThat(r.get("deleted").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.get("message").asText()).startsWith("Removed ");
        assertThat(count(user)).isZero();

        // Policy changes and the purge are themselves in the trail, and the chain still verifies.
        mvc.perform(authed(get("/audit/events")).param("event_type", "admin.audit_retention.policy_set")
                        .param("target", "retention:UNKNOWN"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].details.previous.legal_hold").value(true));
        mvc.perform(authed(get("/audit/events")).param("event_type", "admin.audit_retention.applied").param("limit", "1"))
                .andExpect(jsonPath("$.items[0].details.deleted_by_category.UNKNOWN", notNullValue()));
        mvc.perform(authed(get("/audit/integrity/verify"))).andExpect(jsonPath("$.valid").value(true));

        mvc.perform(authed(post("/audit/retention/policy")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"UNKNOWN\", \"retention_days\": -1}"))
                .andExpect(status().isUnprocessableEntity());
        // Leave UNKNOWN on the default afterwards.
        mvc.perform(authed(post("/audit/retention/policy")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\": \"UNKNOWN\", \"retention_days\": 365}")).andExpect(status().isOk());
    }

    private long count(String user) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id = ?", Long.class, user);
    }

    @Test
    void healthReportsAndOps() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\",\"service\":\"audit-service\"}"));
        mvc.perform(authed(get("/audit/health/services"))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(authed(get("/audit/health/services/postgresql"))).andExpect(status().isOk());
        mvc.perform(authed(get("/audit/reports/cert-expiry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days_ahead").value(90))
                .andExpect(jsonPath("$.expiring").isArray());
        mvc.perform(authed(get("/audit/reports/cert-expiry")).param("days_ahead", "366"))
                .andExpect(status().isUnprocessableEntity());

        postEvent("{\"event_type\": \"authz.access.granted\", \"app_id\": \"analytics-app\"}");
        postEvent("{\"event_type\": \"auth.login.failed\", \"user_id\": \"x\"}");
        mvc.perform(authed(get("/audit/reports/access-analytics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.top_apps[?(@.app_id == 'analytics-app')].access_count").exists())
                .andExpect(jsonPath("$.failed_login_trend[0].day", matchesPattern("\\d{4}-\\d{2}-\\d{2}")))
                .andExpect(jsonPath("$.total_access_denials", notNullValue()));

        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("audit_events_written_total")));
    }

    @Test
    void correlationIdIsEchoedOrMinted() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
        mvc.perform(get("/health").header("X-Correlation-Id", "bad id\n"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
