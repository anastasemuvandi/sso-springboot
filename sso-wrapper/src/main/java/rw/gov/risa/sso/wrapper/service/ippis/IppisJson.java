package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import rw.gov.risa.sso.wrapper.policy.ippisperson.EmploymentStatus;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployeeRecord;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployment;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;

/**
 * Maps the IPPIS wire shapes onto the pure policy records (which carry no Jackson annotations).
 * Inbound and tolerant like the reference's pydantic models: unknown fields are ignored, the feed's
 * camelCase is the contract and snake_case is accepted too ({@code populate_by_name}).
 */
public final class IppisJson {

    public static final Set<String> GENDERS = Set.of("M", "F");
    public static final Set<String> MARITAL_STATUSES = Set.of("S", "M", "D", "W");
    public static final Set<String> EMPLOYEE_GROUPS = Set.of("under_statute", "political_appointee",
            "under_contract", "intern", "diplomat", "casual_worker", "board_member");
    public static final Set<String> REASONS_FOR_CHANGE = Set.of("new", "removed", "changed");

    private IppisJson() {
    }

    /** {@code [...]}, {@code {"people": [...]}} or {@code {"employees": [...]}} (the envelope is not settled with GESB). */
    public static List<IppisPerson> people(JsonNode body) {
        JsonNode items = body;
        if (body != null && body.isObject()) {
            items = body.hasNonNull("people") ? body.get("people") : body.get("employees");
        }
        List<IppisPerson> out = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode n : items) {
                out.add(person(n));
            }
        }
        return out;
    }

    public static IppisPerson person(JsonNode n) {
        String id = text(n, "id", "employee_number");
        if (id == null) {
            throw new IllegalArgumentException("IPPIS person without an id (employee number)");
        }
        List<IppisEmployment> employments = new ArrayList<>();
        JsonNode emps = n.get("employments");
        if (emps != null && emps.isArray()) {
            for (JsonNode e : emps) {
                employments.add(employment(e));
            }
        }
        return new IppisPerson(id, text(n, "nationalId", "national_id"), text(n, "rssbNumber", "rssb_number"),
                text(n, "phone"), text(n, "email"), text(n, "firstName", "first_name"),
                text(n, "lastName", "last_name"), text(n, "gender"), text(n, "maritalStatus", "marital_status"),
                date(text(n, "dateOfBirth", "date_of_birth")), instant(text(n, "createdAt", "created_at")),
                instant(text(n, "updatedAt", "updated_at")), employments);
    }

    static IppisEmployment employment(JsonNode e) {
        String employer = text(e, "employerId", "employer_id");
        if (employer == null) {
            employer = "";
        }
        List<IppisJob> jobs = new ArrayList<>();
        JsonNode js = e.get("jobs");
        if (js != null && js.isArray()) {
            for (JsonNode j : js) {
                jobs.add(job(j));
            }
        }
        return new IppisEmployment(employer, text(e, "employerName", "employer_name"),
                text(e, "sectorId", "sector_id"), text(e, "sectorName", "sector_name"), jobs);
    }

    static IppisJob job(JsonNode j) {
        String id = text(j, "id");
        LocalDate start = date(text(j, "startDate", "start_date"));
        if (id == null || start == null) {
            throw new IllegalArgumentException("IPPIS job without id/startDate");
        }
        return new IppisJob(id, text(j, "positionId", "position_id"), text(j, "positionName", "position_name"),
                text(j, "unitId", "unit_id"), text(j, "unitName", "unit_name"), text(j, "level"),
                bool(j, "hasSubordinates", "has_subordinates"), text(j, "employeeGroup", "employee_group"),
                bool(j, "isSecondaryEmployment", "is_secondary_employment"), bool(j, "isActing", "is_acting"),
                start, date(text(j, "endDate", "end_date")), text(j, "reasonForChange", "reason_for_change"),
                instant(text(j, "createdAt", "created_at")), instant(text(j, "updatedAt", "updated_at")));
    }

    /** The OLD flat contract the push webhook still delivers ({@code IppisEmployee}). */
    public static IppisEmployeeRecord flatEmployee(JsonNode n) {
        String number = text(n, "employee_number", "employeeNumber");
        if (number == null) {
            throw new IllegalArgumentException("employee.employee_number is required");
        }
        String rawStatus = text(n, "status");
        EmploymentStatus status = rawStatus == null ? EmploymentStatus.ACTIVE
                : EmploymentStatus.fromWire(rawStatus).orElseThrow(
                        () -> new IllegalArgumentException("unknown employee.status '" + rawStatus + "'"));
        return new IppisEmployeeRecord(number, text(n, "national_id", "nationalId"),
                text(n, "first_name", "firstName"), text(n, "last_name", "lastName"), text(n, "email"),
                text(n, "phone"), text(n, "institution"), text(n, "position"), status,
                instant(text(n, "updated_at", "updatedAt")));
    }

    // ---- canonical vocabularies (fold display casing; drop unknown values) ----------------

    public static String normalizeEnum(String value, Set<String> allowed) {
        if (value == null) {
            return null;
        }
        String folded = value.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (folded.isEmpty()) {
            return null;
        }
        if (allowed.contains(folded)) {
            return folded;
        }
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : null;
    }

    // ---- scalars ---------------------------------------------------------------------------

    static String text(JsonNode n, String... names) {
        if (n == null) {
            return null;
        }
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull()) {
                return v.isTextual() ? v.asText() : v.toString();
            }
        }
        return null;
    }

    static boolean bool(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull()) {
                return v.isBoolean() ? v.booleanValue() : Boolean.parseBoolean(v.asText());
            }
        }
        return false;
    }

    public static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
    }

    /** ISO-8601 with offset/Z, or a naive local timestamp read as UTC. */
    public static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("unparseable timestamp '" + t + "'", e);
        }
    }
}
