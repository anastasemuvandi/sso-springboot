package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileSummary;

/**
 * What one reconcile run did. {@code summary} carries the per-status counts plus the orthogonal
 * counters ({@code unmapped_institution}, {@code ambiguous_institution}, mirror). {@code rehired}
 * lists accounts re-enabled under the re-hire rule; {@code mirror} is the recorder's summary (null
 * without a mirror).
 */
public record RunReport(
        String mode,
        boolean dryRun,
        Instant since,
        Instant newWatermark,
        ReconcileSummary summary,
        List<String> rehired,
        Map<String, Object> mirror) {

    public RunReport {
        rehired = List.copyOf(rehired);
    }

    /** The distinct IPPIS codes the run could not resolve, sorted. */
    public List<String> unmappedCodes() {
        TreeSet<String> codes = new TreeSet<>();
        for (ReconcileOutcome o : summary.outcomes()) {
            if (o.unmappedInstitution() != null && !o.unmappedInstitution().isEmpty()) {
                codes.add(o.unmappedInstitution());
            }
        }
        return new ArrayList<>(codes);
    }

    /** JSON-friendly view (counts by wire name, never the outcomes list). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("dry_run", dryRun);
        m.put("since", since == null ? null : since.toString());
        m.put("watermark", newWatermark == null ? null : newWatermark.toString());
        m.put("total", summary.total());
        for (IppisOutcome s : IppisOutcome.values()) {
            m.put(s.wire(), summary.count(s));
        }
        m.put("unmapped_institution", summary.unmappedInstitution());
        m.put("ambiguous_institution", summary.ambiguousInstitution());
        m.put("unmapped_codes", unmappedCodes());
        m.put("mirrored", summary.mirrored());
        m.put("mirror_errors", summary.mirrorErrors());
        m.put("mirror_refused", summary.mirrorRefused());
        m.put("rehired", rehired);
        if (mirror != null) {
            m.put("mirror", mirror);
        }
        return m;
    }
}
