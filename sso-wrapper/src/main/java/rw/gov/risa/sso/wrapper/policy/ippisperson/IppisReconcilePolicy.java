package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The per-user decisions of the IPPIS reconcile/leaver/sync passes, minus the I/O (ports of
 * {@code ippis_reconcile._dispatch}, {@code ippis_leaver.apply_leaver}, the early exits of
 * {@code reconcile_user}/{@code enrich_user} and the delta matching).
 */
public final class IppisReconcilePolicy {

    public static final String LEAVER_TRIGGERED_BY = "ippis-leaver-feed";
    public static final String RECONCILE_TRIGGERED_BY = "ippis-reconcile";

    /** What to do with a matched user. */
    public enum Dispatch { OFFBOARD_IF_ENABLED, ENRICH }

    /** {@code apply_leaver}'s decision. */
    public enum LeaverDecision {
        /** Not a leaver: status {@code active}. */
        ACTIVE,
        /** Leaver, account already disabled: idempotent skip. */
        ALREADY_DISABLED,
        /** Leaver with an enabled account: offboard. */
        DISABLE
    }

    /** Mirror match state for a delta record. */
    public enum MatchState { MATCHED, UNMATCHED, AMBIGUOUS }

    private IppisReconcilePolicy() {
    }

    /** The user's employee number (join attribute); empty → outcome {@code skipped}. */
    public static Optional<String> employeeNumberOf(Map<String, List<String>> attributes, String attrName) {
        String v = IppisAttributeProjection.firstAttr(attributes,
                attrName == null ? IppisAttributeProjection.ATTR_EMPLOYEE_NUMBER : attrName);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    public static ReconcileOutcome skipped(String userId, String attrName) {
        return ReconcileOutcome.of(userId, IppisOutcome.SKIPPED, "no " + attrName);
    }

    public static ReconcileOutcome notFound(String userId, String employeeNumber) {
        return ReconcileOutcome.of(userId, IppisOutcome.NOT_FOUND,
                "employee_number " + employeeNumber + " not in IPPIS");
    }

    public static ReconcileOutcome noUser(String attrName, String employeeNumber) {
        return ReconcileOutcome.of(null, IppisOutcome.NO_USER,
                "no Keycloak user with " + attrName + "=" + employeeNumber);
    }

    /** Leavers (no open job anywhere) are offboarded; everybody else is enriched. */
    public static Dispatch dispatch(PersonView view) {
        return view.isLeaver() ? Dispatch.OFFBOARD_IF_ENABLED : Dispatch.ENRICH;
    }

    /** {@code enabled == FALSE} only counts as disabled when explicitly false. */
    public static LeaverDecision leaverDecision(Boolean enabled, PersonView view) {
        if (!view.isLeaver()) {
            return LeaverDecision.ACTIVE;
        }
        if (Boolean.FALSE.equals(enabled)) {
            return LeaverDecision.ALREADY_DISABLED;
        }
        return LeaverDecision.DISABLE;
    }

    /** The offboarding reason string recorded for a leaver. */
    public static String leaverReason(PersonView view) {
        return "IPPIS reports no current employment for " + view.employeeNumber();
    }

    /** Delta pass: how many Keycloak users carry this employee number. */
    public static MatchState matchState(int matches) {
        if (matches <= 0) {
            return MatchState.UNMATCHED;
        }
        return matches > 1 ? MatchState.AMBIGUOUS : MatchState.MATCHED;
    }

    /** The new watermark: the max {@code updatedAt} seen, else {@code since}. */
    public static Instant newWatermark(List<Instant> stamps, Instant since) {
        return stamps.stream().filter(Objects::nonNull).max(Instant::compareTo).orElse(since);
    }
}
