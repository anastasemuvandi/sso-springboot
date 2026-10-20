package rw.gov.risa.sso.ippismock.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rw.gov.risa.sso.ippismock.policy.PersonView;

/**
 * The two wire shapes of the same store ("TWO SHAPES" in the reference docstring).
 * Absent stays absent: a field IPPIS does not hold is omitted rather than sent as null,
 * because the sync treats the two differently ({@code IPPIS_CLEAR_MISSING}).
 */
public final class Projections {

    /**
     * {@code startDate} is required by the nested schema and the flat record has no answer, so every
     * projected job of a flat record starts here — one obviously synthetic constant.
     */
    public static final String SYNTHETIC_START = "2020-01-01";

    private Projections() {
    }

    /** The flat {@code /employees} record. For a nested person, institution/position/status are derived. */
    public static Map<String, Object> flat(StoredPerson p) {
        String institution = p.getInstitution();
        String position = p.getPosition();
        String status = p.getStatus();
        if (p.isNested()) {
            PersonView view = PersonView.of(p.getEmployments());
            institution = view.institution();
            position = view.position();
            status = view.status();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employee_number", p.getEmployeeNumber());
        putNonNull(m, "national_id", p.getNationalId());
        putNonNull(m, "first_name", p.getFirstName());
        putNonNull(m, "last_name", p.getLastName());
        putNonNull(m, "email", p.getEmail());
        putNonNull(m, "phone", p.getPhone());
        putNonNull(m, "institution", institution);
        putNonNull(m, "position", position);
        putNonNull(m, "status", status);
        putNonNull(m, "updated_at", p.getUpdatedAt());
        return m;
    }

    /** The nested GESB {@code /people} person. */
    public static Map<String, Object> person(StoredPerson p) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("id", p.getEmployeeNumber());
        put(person, "updatedAt", p.getUpdatedAt());
        person.put("employments", p.isNested() ? p.getEmployments() : synthesisedEmployments(p));
        put(person, "nationalId", p.getNationalId());
        put(person, "firstName", p.getFirstName());
        put(person, "lastName", p.getLastName());
        put(person, "email", p.getEmail());
        put(person, "phone", p.getPhone());
        put(person, "gender", p.getGender());
        put(person, "maritalStatus", p.getMaritalStatus());
        put(person, "dateOfBirth", p.getDateOfBirth());
        put(person, "createdAt", p.getCreatedAt());
        return person;
    }

    /**
     * The reference projection of a flat record: one employment, one job with the STABLE id
     * {@code <number>-j1} (the upsert key of an append-only table), never secondary. A terminated
     * record's job is closed on the day HR last touched it, or the offboarding path never fires;
     * suspended is NOT a departure and keeps an open job.
     */
    static List<Map<String, Object>> synthesisedEmployments(StoredPerson p) {
        String employer = p.getInstitution();
        if (employer == null || employer.isEmpty()) {
            return List.of();
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", p.getEmployeeNumber() + "-j1");
        job.put("startDate", SYNTHETIC_START);
        job.put("isSecondaryEmployment", false);
        job.put("updatedAt", p.getUpdatedAt());
        put(job, "positionName", p.getPosition());
        if ("terminated".equals(p.getStatus())) {
            String stamp = p.getUpdatedAt();
            job.put("endDate", stamp != null ? stamp.substring(0, Math.min(10, stamp.length())) : SYNTHETIC_START);
            job.put("reasonForChange", "Removed");
        } else {
            job.put("reasonForChange", "New");
        }
        Map<String, Object> employment = new LinkedHashMap<>();
        employment.put("employerId", employer);
        String[] meta = SeedData.EMPLOYERS.get(employer);
        String name = meta == null ? employer : meta[0];
        String sector = meta == null ? null : meta[1];
        employment.put("jobs", new ArrayList<>(List.of(job)));
        put(employment, "employerName", name);
        if (sector != null) {
            employment.put("sectorId", sector);
            put(employment, "sectorName", SeedData.SECTORS.get(sector));
        }
        return List.of(employment);
    }

    /** Flat records are served as stored (an empty string stays an empty string, as in the reference). */
    private static void putNonNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value instanceof String s ? !s.isEmpty() : value != null) {
            m.put(key, value);
        }
    }
}
