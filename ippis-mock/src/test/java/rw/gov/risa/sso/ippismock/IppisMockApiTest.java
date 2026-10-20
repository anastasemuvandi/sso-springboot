package rw.gov.risa.sso.ippismock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.ippismock.store.EmployeeStore;

/** Port of the reference {@code ippis-mock/tests/test_main.py}, plus the multi-employment cases. */
@SpringBootTest
@AutoConfigureMockMvc
class IppisMockApiTest {
    private static final String KEY_HEADER = "X-IPPIS-API-Key";
    private static final String KEY = "change-me-in-ippis";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EmployeeStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    private static MockHttpServletRequestBuilder keyed(MockHttpServletRequestBuilder b) {
        return b.header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> json(MockHttpServletRequestBuilder b) throws Exception {
        String body = mvc.perform(keyed(b)).andReturn().getResponse().getContentAsString();
        return mapper.readValue(body, new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path, String key, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(path);
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return (List<Map<String, Object>>) json(b).get(key);
    }

    // ------------------------------------------------------------------ read contract

    @Test
    void healthNeedsNoKey() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.service").value("ippis-mock"));
    }

    @Test
    void getEmployeeWithValidKey() throws Exception {
        mvc.perform(keyed(get("/employees/EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee_number").value("EMP001"))
                .andExpect(jsonPath("$.institution").value("MINEDUC"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void terminatedEmployeeIsServed() throws Exception {
        mvc.perform(keyed(get("/employees/EMP404"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("terminated"));
    }

    @Test
    void unknownEmployeeIs404WithEnvelope() throws Exception {
        mvc.perform(keyed(get("/employees/NOPE"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("no IPPIS record for employee_number 'NOPE'"));
    }

    @Test
    void missingOrWrongKeyIs401() throws Exception {
        mvc.perform(get("/employees/EMP001")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));
        mvc.perform(get("/employees/EMP001").header(KEY_HEADER, "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void listEmployees() throws Exception {
        Map<String, Object> body = json(get("/employees"));
        assertThat((Integer) body.get("total")).isGreaterThanOrEqualTo(4);
        assertThat(body).doesNotContainKeys("offset", "limit");
    }

    @Test
    void listEmployeesChangedSinceFilters() throws Exception {
        Set<Object> numbers = new HashSet<>();
        list("/employees", "employees", "changed_since", "2026-06-18T00:00:00+00:00")
                .forEach(e -> numbers.add(e.get("employee_number")));
        assertThat(numbers).contains("EMP404").doesNotContain("EMP001");
    }

    @Test
    void changedSinceAcceptsZuluNaiveAndBareDate() throws Exception {
        for (String since : List.of("2026-06-18T00:00:00Z", "2026-06-18T00:00:00", "2026-06-18",
                "2026-06-18T00:00:00 00:00")) {
            Set<Object> numbers = new HashSet<>();
            list("/employees", "employees", "changed_since", since).forEach(e -> numbers.add(e.get("employee_number")));
            assertThat(numbers).as(since).contains("EMP404").doesNotContain("EMP001");
        }
    }

    @Test
    void listEmployeesChangedSinceInvalidIs400() throws Exception {
        mvc.perform(keyed(get("/employees").param("changed_since", "not-a-date")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_changed_since"));
    }

    // ------------------------------------------------------------------ control plane

    @Test
    void enrollNewEmployee() throws Exception {
        mvc.perform(keyed(post("/employees")).content("""
                {"employee_number":"EMP900","first_name":"New","last_name":"Hire",
                 "institution":"MINICT","position":"Analyst","unknown_field":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.unknown_field").doesNotExist());
        mvc.perform(keyed(get("/employees/EMP900"))).andExpect(status().isOk());
    }

    @Test
    void enrollDuplicateIs409() throws Exception {
        mvc.perform(keyed(post("/employees")).content("{\"employee_number\":\"EMP001\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void enrollWithoutNumberIs422() throws Exception {
        mvc.perform(keyed(post("/employees")).content("{\"first_name\":\"X\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enrollRequiresKey() throws Exception {
        mvc.perform(post("/employees").contentType(MediaType.APPLICATION_JSON).content("{\"employee_number\":\"EMP901\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateEmployee() throws Exception {
        mvc.perform(keyed(patch("/employees/EMP001")).content("{\"position\":\"Senior Accountant\",\"employee_number\":\"X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("Senior Accountant"))
                .andExpect(jsonPath("$.employee_number").value("EMP001"))
                .andExpect(jsonPath("$.updated_at", not("2026-06-01T08:00:00+00:00")));
    }

    @Test
    void updateUnknownIs404() throws Exception {
        mvc.perform(keyed(patch("/employees/NOPE")).content("{\"position\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void terminateEmployee() throws Exception {
        mvc.perform(keyed(delete("/employees/EMP002"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("terminated"));
        mvc.perform(keyed(get("/employees/EMP002"))).andExpect(jsonPath("$.status").value("terminated"));
        // ...and the nested projection now has a closed job.
        mvc.perform(keyed(get("/people/EMP002")))
                .andExpect(jsonPath("$.employments[0].jobs[0].endDate").isNotEmpty())
                .andExpect(jsonPath("$.employments[0].jobs[0].reasonForChange").value("Removed"));
    }

    @Test
    void writesAreSeenByDelta() throws Exception {
        mvc.perform(keyed(patch("/employees/EMP001")).content("{\"phone\":\"0788000999\"}")).andExpect(status().isOk());
        Set<Object> ids = new HashSet<>();
        list("/people", "people", "changed_since", "2026-10-01T00:00:00+00:00").forEach(p -> ids.add(p.get("id")));
        assertThat(ids).contains("EMP001");
    }

    // ------------------------------------------------------------------ nested (GESB) projection

    @Test
    void peopleListsEveryoneTheFlatFeedHas() throws Exception {
        List<Map<String, Object>> flat = list("/employees", "employees");
        List<Map<String, Object>> nested = list("/people", "people");
        assertThat(nested).hasSameSizeAs(flat);
        assertThat(nested.stream().map(p -> p.get("id")).toList())
                .containsExactlyElementsOf(flat.stream().map(e -> e.get("employee_number")).toList());
    }

    @Test
    void peopleNeedsTheApiKey() throws Exception {
        mvc.perform(get("/people")).andExpect(status().isUnauthorized());
        mvc.perform(get("/people/EMP001")).andExpect(status().isUnauthorized());
    }

    @Test
    void personIsNestedPersonEmploymentJob() throws Exception {
        mvc.perform(keyed(get("/people/EMP001")))
                .andExpect(jsonPath("$.id").value("EMP001"))
                .andExpect(jsonPath("$.nationalId").value("1199000000000001"))
                .andExpect(jsonPath("$.employments.length()").value(1))
                .andExpect(jsonPath("$.employments[0].employerId").value("MINEDUC"))
                .andExpect(jsonPath("$.employments[0].employerName").value("Ministry of Education"))
                .andExpect(jsonPath("$.employments[0].sectorId").value("04"))
                .andExpect(jsonPath("$.employments[0].sectorName").value("Education"))
                .andExpect(jsonPath("$.employments[0].jobs.length()").value(1))
                .andExpect(jsonPath("$.employments[0].jobs[0].positionName").value("Accountant"))
                .andExpect(jsonPath("$.employments[0].jobs[0].startDate").value("2020-01-01"))
                .andExpect(jsonPath("$.employments[0].jobs[0].isSecondaryEmployment").value(false));
    }

    @Test
    void jobIdIsStableAcrossPulls() throws Exception {
        Object first = json(get("/people/EMP001")).get("employments");
        Object second = json(get("/people/EMP001")).get("employments");
        assertThat(first).isEqualTo(second);
        mvc.perform(keyed(get("/people/EMP001"))).andExpect(jsonPath("$.employments[0].jobs[0].id").value("EMP001-j1"));
    }

    @Test
    void terminatedEmployeeHasAClosedJob() throws Exception {
        mvc.perform(keyed(get("/people/EMP404")))
                .andExpect(jsonPath("$.employments[0].jobs[0].endDate").value("2026-07-01"))
                .andExpect(jsonPath("$.employments[0].jobs[0].reasonForChange").value("Removed"));
    }

    @Test
    void suspendedEmployeeKeepsAnOpenJob() throws Exception {
        mvc.perform(keyed(get("/people/EMP003")))
                .andExpect(jsonPath("$.employments[0].jobs[0].endDate").doesNotExist())
                .andExpect(jsonPath("$.employments[0].jobs[0].reasonForChange").value("New"));
    }

    @Test
    void absentFieldsAreOmittedNotNull() throws Exception {
        mvc.perform(keyed(get("/people/EMP002")))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.email").value("aline.uwase@minisante.gov.rw"));
        mvc.perform(keyed(get("/employees/EMP1010"))).andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(keyed(get("/employees/EMP1009")))
                .andExpect(jsonPath("$.national_id").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void peopleHonoursChangedSince() throws Exception {
        int everyone = (Integer) json(get("/people")).get("total");
        int later = (Integer) json(get("/people").param("changed_since", "2026-07-01T00:00:00+00:00")).get("total");
        assertThat(later).isPositive().isLessThan(everyone);
        mvc.perform(keyed(get("/people").param("changed_since", "not-a-date"))).andExpect(status().isBadRequest());
    }

    @Test
    void unknownPersonIs404() throws Exception {
        mvc.perform(keyed(get("/people/NOPE"))).andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEmployerResolvesToANameAndThereAreTen() throws Exception {
        Set<Object> employers = new HashSet<>();
        for (Map<String, Object> person : list("/people", "people")) {
            for (Map<String, Object> employment : (List<Map<String, Object>>) person.get("employments")) {
                assertThat(employment.get("employerName")).as(person.get("id") + " employer name").isNotNull();
                employers.add(employment.get("employerId"));
            }
        }
        assertThat(employers).containsExactlyInAnyOrder("MINAGRI", "MINISANTE", "MINEDUC", "MININFRA", "MIFOTRA",
                "RDB", "RRA", "MINIJUST", "MINEACOM", "MOH");
    }

    // ------------------------------------------------------------------ multi-employment edge cases

    @Test
    void dualAppointeeHasTwoEmploymentsAndIsPrimaryInOne() throws Exception {
        mvc.perform(keyed(get("/people/EMP2009")))
                .andExpect(jsonPath("$.employments.length()").value(2))
                .andExpect(jsonPath("$.employments[0].employerId").value("MINAGRI"))
                .andExpect(jsonPath("$.employments[0].jobs[0].isSecondaryEmployment").value(false))
                .andExpect(jsonPath("$.employments[1].employerId").value("MINISANTE"))
                .andExpect(jsonPath("$.employments[1].jobs[0].isSecondaryEmployment").value(true))
                .andExpect(jsonPath("$.employments[1].jobs[0].endDate").doesNotExist());
        mvc.perform(keyed(get("/employees/EMP2009")))
                .andExpect(jsonPath("$.institution").value("MINAGRI"))
                .andExpect(jsonPath("$.position").value("Chief Veterinary Officer"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.employments").doesNotExist());
    }

    @Test
    void nestedLeaverHasNoOpenJobAnywhere() throws Exception {
        mvc.perform(keyed(get("/people/EMP2008"))).andExpect(jsonPath("$.employments[0].jobs[0].endDate").value("2026-06-30"));
        mvc.perform(keyed(get("/employees/EMP2008")))
                .andExpect(jsonPath("$.status").value("terminated"))
                .andExpect(jsonPath("$.institution").value("MINAGRI"));
    }

    @Test
    void resigningOneOfTwoPostsIsAMoveNotAnExit() throws Exception {
        mvc.perform(keyed(get("/people/EMP6001")))
                .andExpect(jsonPath("$.employments[0].jobs[0].endDate").value("2026-08-31"))
                .andExpect(jsonPath("$.employments[1].jobs[0].endDate").doesNotExist());
        mvc.perform(keyed(get("/employees/EMP6001")))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.institution").value("MINAGRI"));
    }

    @Test
    void ambiguousPrimaryLeavesInstitutionUnwritten() throws Exception {
        mvc.perform(keyed(get("/employees/EMP6002")))
                .andExpect(jsonPath("$.institution").doesNotExist())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(keyed(get("/employees/EMP6003"))).andExpect(jsonPath("$.institution").value("MININFRA"));
    }

    @Test
    void deletingANestedPersonClosesEveryOpenJob() throws Exception {
        mvc.perform(keyed(delete("/employees/EMP2009"))).andExpect(jsonPath("$.status").value("terminated"));
        mvc.perform(keyed(get("/people/EMP2009")))
                .andExpect(jsonPath("$.employments[0].jobs[0].endDate").isNotEmpty())
                .andExpect(jsonPath("$.employments[1].jobs[0].endDate").isNotEmpty())
                .andExpect(jsonPath("$.employments[1].jobs[0].reasonForChange").value("Removed"));
    }

    @Test
    void patchingInstitutionOfANestedPersonCollapsesToFlat() throws Exception {
        mvc.perform(keyed(patch("/employees/EMP2009")).content("{\"institution\":\"RDB\"}"))
                .andExpect(jsonPath("$.institution").value("RDB"))
                .andExpect(jsonPath("$.position").value("Chief Veterinary Officer"));
        mvc.perform(keyed(get("/people/EMP2009")))
                .andExpect(jsonPath("$.employments.length()").value(1))
                .andExpect(jsonPath("$.employments[0].jobs[0].id").value("EMP2009-j1"));
    }

    @Test
    void putPersonCreatesADualAppointment() throws Exception {
        mvc.perform(keyed(put("/people/EMP7001")).content("""
                {"firstName":"Test","lastName":"Dual","email":"test.dual@rra.gov.rw",
                 "employments":[
                   {"employerId":"RRA","jobs":[{"id":"j7001a","startDate":"2019-01-01","positionName":"Auditor"}]},
                   {"employerId":"RDB","jobs":[{"id":"j7001b","startDate":"2024-01-01","isSecondaryEmployment":true}]}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employments[0].employerName").value("Rwanda Revenue Authority"))
                .andExpect(jsonPath("$.employments[1].jobs[0].isSecondaryEmployment").value(true))
                .andExpect(jsonPath("$.employments[1].jobs[0].reasonForChange").value("New"));
        mvc.perform(keyed(get("/employees/EMP7001"))).andExpect(jsonPath("$.institution").value("RRA"));
        mvc.perform(keyed(put("/people/EMP7001")).content("{\"id\":\"OTHER\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(keyed(put("/people/EMP7001")).content("{\"employments\":[{\"jobs\":[]}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ pagination (extension)

    @Test
    @SuppressWarnings("unchecked")
    void paginationSlicesInStableOrder() throws Exception {
        List<Map<String, Object>> all = list("/people", "people");
        Map<String, Object> page = json(get("/people").param("limit", "5").param("offset", "5"));
        assertThat(page.get("total")).isEqualTo(all.size());
        assertThat(page.get("offset")).isEqualTo(5);
        assertThat((List<Map<String, Object>>) page.get("people")).extracting(p -> p.get("id"))
                .containsExactlyElementsOf(all.subList(5, 10).stream().map(p -> p.get("id")).toList());
        mvc.perform(keyed(get("/employees").param("limit", "0"))).andExpect(status().isBadRequest());
        mvc.perform(keyed(get("/employees").param("offset", "999"))).andExpect(jsonPath("$.employees.length()").value(0));
    }

    @Test
    void resetRestoresTheSeed() throws Exception {
        mvc.perform(keyed(delete("/employees/EMP001")));
        mvc.perform(keyed(post("/_mock/reset"))).andExpect(status().isOk());
        mvc.perform(keyed(get("/employees/EMP001"))).andExpect(jsonPath("$.status").value("active"));
        mvc.perform(post("/_mock/reset")).andExpect(status().isUnauthorized());
        mvc.perform(keyed(get("/employees"))).andExpect(jsonPath("$.employees[*].employee_number", hasItem("EMP6002")));
    }
}
