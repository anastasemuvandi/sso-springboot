package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Batch summary: a count per status plus the orthogonal counters. */
public record ReconcileSummary(
        int total,
        Map<IppisOutcome, Integer> byStatus,
        int unmappedInstitution,
        int ambiguousInstitution,
        int mirrored,
        int mirrorErrors,
        int mirrorRefused,
        List<ReconcileOutcome> outcomes) {

    public ReconcileSummary {
        byStatus = Map.copyOf(byStatus);
        outcomes = List.copyOf(outcomes);
    }

    public int count(IppisOutcome status) {
        return byStatus.getOrDefault(status, 0);
    }

    /**
     * Counts from the recorder when a mirror was configured (refusals — reassigned employee
     * numbers, conflicts — are not errors), else from the outcomes.
     */
    public record MirrorCounts(int errors, int reassignments, int conflicts) {
    }

    public static ReconcileSummary of(List<ReconcileOutcome> outcomes, MirrorCounts mirror) {
        Map<IppisOutcome, Integer> counts = new EnumMap<>(IppisOutcome.class);
        for (IppisOutcome s : IppisOutcome.values()) {
            counts.put(s, 0);
        }
        int unmapped = 0;
        int ambiguous = 0;
        int mirrored = 0;
        int mirrorErrorsFromOutcomes = 0;
        for (ReconcileOutcome o : outcomes) {
            counts.merge(o.status(), 1, Integer::sum);
            if (o.unmappedInstitution() != null && !o.unmappedInstitution().isEmpty()) {
                unmapped++;
            }
            if (o.ambiguousInstitution() != null && !o.ambiguousInstitution().isEmpty()) {
                ambiguous++;
            }
            if (o.mirrored()) {
                mirrored++;
            }
            if (o.mirrorError() != null && !o.mirrorError().isEmpty()) {
                mirrorErrorsFromOutcomes++;
            }
        }
        int errors = mirror != null ? mirror.errors() : mirrorErrorsFromOutcomes;
        int refused = mirror != null ? mirror.reassignments() + mirror.conflicts() : 0;
        return new ReconcileSummary(outcomes.size(), counts, unmapped, ambiguous, mirrored, errors, refused, outcomes);
    }
}
