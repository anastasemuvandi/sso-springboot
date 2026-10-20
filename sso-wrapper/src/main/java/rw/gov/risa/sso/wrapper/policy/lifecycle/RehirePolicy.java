package rw.gov.risa.sso.wrapper.policy.lifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rw.gov.risa.sso.wrapper.policy.ippisperson.PersonView;

/**
 * The explicit, audited re-hire rule (spec §5.8: "Re-enable re-hires under an explicit,
 * audited rule"). The reference wrapper never re-enables; this is the spec's addition.
 *
 * <p>A disabled account is re-enabled automatically ONLY when all hold:
 * <ol>
 *   <li>the account is disabled;</li>
 *   <li>IPPIS now shows an open post somewhere ({@code !view.isLeaver()}) and the record is not
 *       an empty/unreadable one (it has at least one employer);</li>
 *   <li>the account was disabled BY THE IPPIS FEED ({@code offboarded_by} is one of
 *       {@link #AUTOMATIC_TRIGGERS}). An account an admin offboarded by hand is never
 *       re-enabled by a feed.</li>
 * </ol>
 * Re-enabling restores the account only: the stripped roles, groups and named grants are
 * NOT restored — access is granted afresh. The offboarding markers are cleared.
 */
public final class RehirePolicy {

    /** Triggers whose offboarding a re-hire may reverse. */
    public static final Set<String> AUTOMATIC_TRIGGERS = Set.of("ippis-leaver-feed", "ippis-reconcile");

    public enum Decision {
        /** Account is enabled — nothing to do. */
        NOT_DISABLED("not_disabled"),
        /** IPPIS still shows no open post. */
        STILL_LEAVER("still_leaver"),
        /** IPPIS shows no employment at all — not evidence of a re-hire. */
        NO_EMPLOYMENT("no_employment"),
        /** Disabled by a person (or unknown): stays disabled; report for a human. */
        MANUAL_DISABLE_KEPT("manual_disable_kept"),
        /** Re-enable (audited {@code lifecycle.rehire}). */
        REENABLE("reenable");

        private final String wire;

        Decision(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Audit event code for an automatic re-enable. */
    public static final String AUDIT_EVENT = "lifecycle.rehire";

    private RehirePolicy() {
    }

    public static Decision decide(Boolean enabled, PersonView view, Map<String, List<String>> attributes) {
        if (!Boolean.FALSE.equals(enabled)) {
            return Decision.NOT_DISABLED;
        }
        if (view.isLeaver()) {
            return Decision.STILL_LEAVER;
        }
        if (view.allInstitutionCodes().isEmpty()) {
            return Decision.NO_EMPLOYMENT;
        }
        String by = first(attributes, OffboardingPlanner.ATTR_OFFBOARDED_BY);
        if (by == null || !AUTOMATIC_TRIGGERS.contains(by)) {
            return Decision.MANUAL_DISABLE_KEPT;
        }
        return Decision.REENABLE;
    }

    /** Attribute writes on re-enable: clear both markers (null = remove). */
    public static Map<String, List<String>> clearMarkers() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(OffboardingPlanner.ATTR_OFFBOARDED_BY, null);
        out.put(OffboardingPlanner.ATTR_OFFBOARDED_AT, null);
        return out;
    }

    /** Audit {@code extra} for the re-enable. */
    public static Map<String, Object> auditExtra(String userId, String username, PersonView view,
                                                 Map<String, List<String>> attributes) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("user_id", userId);
        extra.put("target_user", userId);
        extra.put("target_username", username);
        extra.put("employee_number", view.employeeNumber());
        extra.put("offboarded_by", first(attributes, OffboardingPlanner.ATTR_OFFBOARDED_BY));
        extra.put("offboarded_at", first(attributes, OffboardingPlanner.ATTR_OFFBOARDED_AT));
        extra.put("rule", "ippis_open_post_after_feed_offboarding");
        return extra;
    }

    private static String first(Map<String, List<String>> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        List<String> v = attributes.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
