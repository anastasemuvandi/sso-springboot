package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditQuery;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient;

/**
 * {@code GET /institution-admin/audit}: the institution's own audit trail (port of
 * {@code list_institution_audit} + {@code _enforce_institution_scope}).
 *
 * <p>The institution filter is the RESOLVED context's, forced into the downstream query — no request
 * input can widen it. The answer is then filtered again here (an audit-service that ignored the filter,
 * or events with no institution, must not leak): {@code total} is recomputed from what survives and
 * {@code scope_filtered_out} says how many were dropped. The audit-service key is added server-side.
 *
 * <p>Reads through the platform audit client's {@link AuditServiceClient#listEventsForInstitution}
 * (API key added server-side; downstream errors surface as the platform audit proxy's do).
 */
@Service
public class InstitutionAuditService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionAuditService.class);

    /** Query filters a caller may pass (the institution is never one of them). */
    public record Filters(String eventType, String category, String target, String severity, String fromDate,
                             String toDate, int limit, int offset) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditServiceClient client;

    public InstitutionAuditService(AuditServiceClient client) {
        this.client = client;
    }

    public Map<String, Object> events(InstitutionContext ctx, Filters q) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        // listEventsForInstitution pins the institution (replacing any value) and refuses a blank one.
        AuditQuery query = new AuditQuery(q.eventType(), q.category(), null, null, null, null, q.target(),
                q.severity(), q.fromDate(), q.toDate(), q.limit(), q.offset());
        JsonNode page = client.listEventsForInstitution(ctx.institution(), query);
        return enforceInstitutionScope(page == null ? null : JSON.convertValue(page, Object.class), ctx.institution());
    }

    /** Drop every event that is not {@code institution}'s, whatever the downstream returned. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> enforceInstitutionScope(Object page, String institution) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(page instanceof Map<?, ?> map)) {
            out.put("total", 0);
            out.put("limit", 0);
            out.put("offset", 0);
            out.put("items", List.of());
            return out;
        }
        List<Object> items = map.get("items") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        List<Object> kept = new ArrayList<>();
        for (Object e : items) {
            if (e instanceof Map<?, ?> ev && institution.equals(institutionOf((Map<String, Object>) ev))) {
                kept.add(e);
            }
        }
        int dropped = items.size() - kept.size();
        if (dropped > 0) {
            log.error("institution audit scope: dropped {} of {} event(s) not belonging to '{}' — the audit-service "
                    + "did not apply the institution filter.", dropped, items.size(), institution);
        }
        out.put("total", kept.size());
        out.put("limit", map.containsKey("limit") ? map.get("limit") : items.size());
        out.put("offset", map.containsKey("offset") ? map.get("offset") : 0);
        out.put("items", kept);
        if (dropped > 0) {
            out.put("scope_filtered_out", dropped);
        }
        return out;
    }

    private static String institutionOf(Map<String, Object> event) {
        Object inst = event.get("institution");
        if (inst instanceof String s && !s.isBlank()) {
            return s;
        }
        if (event.get("details") instanceof Map<?, ?> d && d.get("institution") instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        return null;
    }
}
