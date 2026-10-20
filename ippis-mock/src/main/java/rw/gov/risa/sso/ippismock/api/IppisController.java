package rw.gov.risa.sso.ippismock.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rw.gov.risa.sso.ippismock.policy.PersonView;
import rw.gov.risa.sso.ippismock.store.EmployeeStore;
import rw.gov.risa.sso.ippismock.store.Employment;
import rw.gov.risa.sso.ippismock.store.Job;
import rw.gov.risa.sso.ippismock.store.Projections;
import rw.gov.risa.sso.ippismock.store.SeedData;
import rw.gov.risa.sso.ippismock.store.StoredPerson;
import rw.gov.risa.sso.ippismock.webhook.WebhookEmitter;

/**
 * The read contract the real IPPIS must satisfy, plus the mock control plane.
 *
 * <pre>
 * GET    /health                       200 (no key)
 * GET    /employees[?changed_since=]   {"employees": [...], "total": n}     flat feed
 * GET    /employees/{number}           flat record | 404
 * GET    /people[?changed_since=]      {"people": [...], "total": n}        nested GESB feed
 * GET    /people/{number}              nested person | 404
 * --- mock control plane (never called by the SSO) ---
 * POST   /employees                    enroll (201) | 409
 * PATCH  /employees/{number}           update | 404
 * DELETE /employees/{number}           terminate (soft) | 404
 * PUT    /people/{number}              upsert a nested person (multi-employment)
 * POST   /_mock/reset                  back to the seeded roster
 * </pre>
 * Both list endpoints also take optional {@code limit}/{@code offset} (an extension: the reference
 * has none; without them the response is exactly the reference's).
 */
@RestController
public class IppisController {
    private static final Set<String> FLAT_FIELDS = Set.of(
            "national_id", "first_name", "last_name", "email", "phone", "institution", "position", "status");
    private static final Set<String> EMPLOYMENT_FIELDS = Set.of("institution", "position", "status");
    private static final int MAX_LIMIT = 1000;

    private final EmployeeStore store;
    private final WebhookEmitter webhook;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IppisController(EmployeeStore store, WebhookEmitter webhook, ObjectMapper mapper) {
        this.store = store;
        this.webhook = webhook;
        this.mapper = mapper;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "ippis-mock");
    }

    // ------------------------------------------------------------------ read contract

    @GetMapping("/employees")
    public Map<String, Object> listEmployees(
            @RequestParam(name = "changed_since", required = false) String changedSince,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return page("employees", filterChangedSince(store.all(), changedSince), Projections::flat, limit, offset);
    }

    @GetMapping("/employees/{employeeNumber}")
    public Map<String, Object> getEmployee(@PathVariable String employeeNumber) {
        return Projections.flat(store.get(employeeNumber).orElseThrow(() -> ApiException.notFound(employeeNumber)));
    }

    /**
     * The nested GESB feed, what the reconcile reads to fill the mirror. Envelope {@code {"people": [...]}}.
     */
    @GetMapping("/people")
    public Map<String, Object> listPeople(
            @RequestParam(name = "changed_since", required = false) String changedSince,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return page("people", filterChangedSince(store.all(), changedSince), Projections::person, limit, offset);
    }

    /** One person, nested. 404 is definitive: the reconcile reads it as {@code not_found}. */
    @GetMapping("/people/{employeeNumber}")
    public Map<String, Object> getPerson(@PathVariable String employeeNumber) {
        return Projections.person(store.get(employeeNumber).orElseThrow(() -> ApiException.notFound(employeeNumber)));
    }

    // ------------------------------------------------------------------ mock control plane

    @PostMapping("/employees")
    public ResponseEntity<Map<String, Object>> enroll(@RequestBody JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.path("employee_number").isTextual()) {
            throw ApiException.invalid("employee_number is required");
        }
        StoredPerson p = new StoredPerson();
        p.setEmployeeNumber(payload.get("employee_number").asText());
        p.setStatus("active");
        applyFlatFields(p, payload);
        if (p.getStatus() == null) {
            p.setStatus("active");
        }
        p.setUpdatedAt(Timestamps.now(clock));
        if (!store.create(p)) {
            throw new ApiException(HttpStatus.CONFLICT, "conflict",
                    "employee '" + p.getEmployeeNumber() + "' already exists");
        }
        Map<String, Object> flat = Projections.flat(p);
        webhook.emit(WebhookEmitter.eventTypeFor(flat.get("status")), flat);
        return ResponseEntity.status(HttpStatus.CREATED).body(flat);
    }

    /** Partial update: only provided fields change; {@code employee_number} is immutable. */
    @PatchMapping("/employees/{employeeNumber}")
    public Map<String, Object> update(@PathVariable String employeeNumber, @RequestBody JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw ApiException.invalid("body must be a JSON object");
        }
        String now = Timestamps.now(clock);
        StoredPerson updated = store.update(employeeNumber, p -> {
            if (p.isNested()) {
                patchNested(p, payload, now);
            } else {
                applyFlatFields(p, payload);
            }
            p.setUpdatedAt(now);
            return p;
        }).orElseThrow(() -> ApiException.notFound(employeeNumber));
        Map<String, Object> flat = Projections.flat(updated);
        webhook.emit(WebhookEmitter.eventTypeFor(flat.get("status")), flat);
        return flat;
    }

    /** Terminate (soft: the record is still served, mirroring real IPPIS). Drives the leaver path. */
    @DeleteMapping("/employees/{employeeNumber}")
    public Map<String, Object> terminate(@PathVariable String employeeNumber) {
        String now = Timestamps.now(clock);
        StoredPerson updated = store.update(employeeNumber, p -> {
            if (p.isNested()) {
                closeAllOpenJobs(p, now);
            }
            p.setStatus("terminated");
            p.setUpdatedAt(now);
            return p;
        }).orElseThrow(() -> ApiException.notFound(employeeNumber));
        Map<String, Object> flat = Projections.flat(updated);
        webhook.emit(WebhookEmitter.TERMINATED, flat);
        return flat;
    }

    /**
     * Upsert a person in the nested shape — how an operator creates a dual appointment, a mover or an
     * ambiguous primary at runtime. Body keys are the feed's camelCase; {@code id} must match the path.
     */
    @PutMapping("/people/{employeeNumber}")
    public ResponseEntity<Map<String, Object>> putPerson(@PathVariable String employeeNumber,
            @RequestBody JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw ApiException.invalid("body must be a JSON object");
        }
        if (payload.hasNonNull("id") && !employeeNumber.equals(payload.get("id").asText())) {
            throw ApiException.invalid("id in the body does not match the path");
        }
        String now = Timestamps.now(clock);
        StoredPerson p = new StoredPerson();
        p.setEmployeeNumber(employeeNumber);
        p.setNationalId(text(payload, "nationalId"));
        p.setFirstName(text(payload, "firstName"));
        p.setLastName(text(payload, "lastName"));
        p.setEmail(text(payload, "email"));
        p.setPhone(text(payload, "phone"));
        p.setGender(text(payload, "gender"));
        p.setMaritalStatus(text(payload, "maritalStatus"));
        p.setDateOfBirth(text(payload, "dateOfBirth"));
        p.setCreatedAt(text(payload, "createdAt"));
        p.setUpdatedAt(now);
        p.setEmployments(readEmployments(payload.path("employments"), now));
        p.setStatus(PersonView.of(p.getEmployments()).status());
        boolean created = store.put(p);
        Map<String, Object> flat = Projections.flat(p);
        webhook.emit(WebhookEmitter.eventTypeFor(flat.get("status")), flat);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(Projections.person(p));
    }

    @PostMapping("/_mock/reset")
    public Map<String, Object> reset() {
        store.reset();
        return Map.of("status", "reset", "total", store.all().size());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> page(String key, List<StoredPerson> records,
            Function<StoredPerson, Map<String, Object>> projection, Integer limit, Integer offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (limit == null && offset == null) {
            body.put(key, records.stream().map(projection).toList());
            body.put("total", records.size());
            return body;
        }
        int off = offset == null ? 0 : offset;
        int lim = limit == null ? MAX_LIMIT : limit;
        if (off < 0 || lim < 1 || lim > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_pagination",
                    "offset must be >= 0 and limit between 1 and " + MAX_LIMIT);
        }
        List<StoredPerson> slice = records.subList(Math.min(off, records.size()),
                Math.min(off + lim, records.size()));
        body.put(key, slice.stream().map(projection).toList());
        body.put("total", records.size());
        body.put("offset", off);
        body.put("limit", lim);
        return body;
    }

    /** Records stamped at/after {@code changed_since}; one filter for both shapes. */
    static List<StoredPerson> filterChangedSince(List<StoredPerson> records, String changedSince) {
        if (changedSince == null || changedSince.isEmpty()) {
            return records;
        }
        OffsetDateTime since;
        try {
            since = Timestamps.parse(changedSince);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_changed_since",
                    "invalid changed_since '" + changedSince + "'");
        }
        List<StoredPerson> out = new ArrayList<>();
        for (StoredPerson r : records) {
            if (r.getUpdatedAt() == null || r.getUpdatedAt().isEmpty()) {
                continue;
            }
            try {
                if (!Timestamps.parse(r.getUpdatedAt()).isBefore(since)) {
                    out.add(r);
                }
            } catch (DateTimeParseException ignored) {
                // an unreadable stamp is never "changed since"
            }
        }
        return out;
    }

    private static void applyFlatFields(StoredPerson p, JsonNode payload) {
        for (Iterator<String> it = payload.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!FLAT_FIELDS.contains(field)) {
                continue; // extra = ignore, and employee_number is immutable
            }
            JsonNode v = payload.get(field);
            String value = v == null || v.isNull() ? null : v.asText();
            switch (field) {
                case "national_id" -> p.setNationalId(value);
                case "first_name" -> p.setFirstName(value);
                case "last_name" -> p.setLastName(value);
                case "email" -> p.setEmail(value);
                case "phone" -> p.setPhone(value);
                case "institution" -> p.setInstitution(value);
                case "position" -> p.setPosition(value);
                case "status" -> p.setStatus(value);
                default -> { }
            }
        }
    }

    /**
     * A flat PATCH on a person stored with real employments. Identity fields apply directly. A
     * {@code status: terminated} alone closes every open job (they become a leaver in both shapes).
     * Any other change to institution/position/status collapses the person to a flat record, because
     * a single institution cannot be written into several employments without inventing which one.
     */
    private static void patchNested(StoredPerson p, JsonNode payload, String now) {
        boolean touchesEmployment = false;
        for (Iterator<String> it = payload.fieldNames(); it.hasNext(); ) {
            touchesEmployment |= EMPLOYMENT_FIELDS.contains(it.next());
        }
        boolean terminateOnly = touchesEmployment
                && payload.has("status") && "terminated".equals(payload.path("status").asText())
                && !payload.has("institution") && !payload.has("position");
        if (terminateOnly) {
            closeAllOpenJobs(p, now);
        } else if (touchesEmployment) {
            PersonView view = PersonView.of(p.getEmployments());
            p.setInstitution(view.institution());
            p.setPosition(view.position());
            p.setStatus(view.status());
            p.setEmployments(null);
        }
        applyFlatFields(p, payload);
    }

    private static void closeAllOpenJobs(StoredPerson p, String now) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<Employment> closed = new ArrayList<>();
        for (Employment e : p.getEmployments()) {
            List<Job> jobs = e.jobs().stream().map(j -> j.isCurrent() ? j.closed(today, now) : j).toList();
            closed.add(new Employment(e.employerId(), e.employerName(), e.sectorId(), e.sectorName(), jobs));
        }
        p.setEmployments(closed);
    }

    private List<Employment> readEmployments(JsonNode node, String now) {
        if (node.isMissingNode() || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw ApiException.invalid("employments must be a list");
        }
        List<Employment> out = new ArrayList<>();
        for (JsonNode e : node) {
            Employment raw;
            try {
                raw = mapper.treeToValue(e, Employment.class);
            } catch (Exception ex) {
                throw ApiException.invalid("invalid employment: " + ex.getMessage());
            }
            if (raw.employerId() == null || raw.employerId().isBlank()) {
                throw ApiException.invalid("employerId is required on every employment");
            }
            List<Job> jobs = new ArrayList<>();
            for (Job j : raw.jobs()) {
                if (j.id() == null || j.startDate() == null) {
                    throw ApiException.invalid("id and startDate are required on every job");
                }
                try {
                    LocalDate.parse(j.startDate());
                    if (j.endDate() != null) {
                        LocalDate.parse(j.endDate());
                    }
                } catch (DateTimeParseException ex) {
                    throw ApiException.invalid("startDate/endDate must be ISO dates (YYYY-MM-DD)");
                }
                jobs.add(j.updatedAt() != null ? j : new Job(j.id(), j.positionId(), j.positionName(), j.unitId(),
                        j.unitName(), j.level(), j.hasSubordinates(), j.employeeGroup(), j.isSecondaryEmployment(),
                        j.isActing(), j.startDate(), j.endDate(),
                        j.reasonForChange() != null ? j.reasonForChange() : (j.endDate() == null ? "New" : "Removed"),
                        j.createdAt(), now));
            }
            String[] meta = SeedData.EMPLOYERS.get(raw.employerId());
            String name = raw.employerName() != null ? raw.employerName() : (meta == null ? raw.employerId() : meta[0]);
            String sector = raw.sectorId() != null ? raw.sectorId() : (meta == null ? null : meta[1]);
            String sectorName = raw.sectorName() != null ? raw.sectorName()
                    : (sector == null ? null : SeedData.SECTORS.get(sector));
            out.add(new Employment(raw.employerId(), name, sector, sectorName, jobs));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
