package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The attribute write one IPPIS record calls for. {@code write} maps attribute name to its new
 * single value; a {@code null} value means CLEAR the attribute ({@code IPPIS_CLEAR_MISSING}).
 * {@code unmappedInstitution} / {@code ambiguousInstitution} are reported on every outcome,
 * changed or not.
 */
public record AttributeSyncPlan(
        Map<String, String> write,
        String unmappedInstitution,
        String ambiguousInstitution,
        String institutionBefore,
        String institutionAfter) {

    public AttributeSyncPlan {
        write = Collections.unmodifiableMap(new LinkedHashMap<>(write));
    }

    /** {@code unchanged} when there is nothing to write, otherwise the write is pending. */
    public boolean isUnchanged() {
        return write.isEmpty();
    }

    /** Sorted attribute names written — the audit {@code attributes} field. */
    public List<String> changedKeys() {
        return List.copyOf(new TreeSet<>(write.keySet()));
    }

    /**
     * Audit {@code extra} for {@code ippis.sync.attributes}: target, attributes, the new
     * institution, and {@code institution_before} only when it is a move.
     */
    public Map<String, Object> auditExtra(String userId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("target_user", userId);
        extra.put("attributes", changedKeys());
        if (institutionAfter != null) {
            extra.put("institution", institutionAfter);
        }
        if (institutionBefore != null && !institutionBefore.equals(institutionAfter)) {
            extra.put("institution_before", institutionBefore);
        }
        return extra;
    }
}
