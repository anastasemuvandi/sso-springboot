package rw.gov.risa.sso.wrapper.policy.lifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rw.gov.risa.sso.wrapper.policy.audit.AuditScope;

/**
 * Leaver offboarding (spec §5.8 / invariant 9), extending the reference's
 * {@code terminate_employee} (sessions → disable → groups) with the removals the spec adds:
 * {@code inst:<slug>:*} realm roles and {@code sso-access-users} named grants. Also the
 * rollback rule: re-enable and re-add whatever was removed; sessions cannot be restored.
 */
public final class OffboardingPlanner {

    /** Realm-role namespace stripped on offboarding. */
    public static final String INSTITUTION_ROLE_PREFIX = "inst:";
    /** Attribute recording who/what disabled the account (read by the re-hire rule). */
    public static final String ATTR_OFFBOARDED_BY = "offboarded_by";
    /** Attribute recording when the account was offboarded (ISO-8601 instant). */
    public static final String ATTR_OFFBOARDED_AT = "offboarded_at";

    private OffboardingPlanner() {
    }

    public static boolean isInstitutionRole(String roleName) {
        return roleName != null && roleName.startsWith(INSTITUTION_ROLE_PREFIX);
    }

    public static OffboardingPlan plan(OffboardingInput input, String reason, String triggeredBy, Instant now) {
        List<String> roles = input.realmRoleNames().stream()
                .filter(OffboardingPlanner::isInstitutionRole).distinct().toList();
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put(ATTR_OFFBOARDED_BY, List.of(triggeredBy == null ? "" : triggeredBy));
        markers.put(ATTR_OFFBOARDED_AT, List.of(now.toString()));
        return new OffboardingPlan(
                input.userId(),
                input.username(),
                AuditScope.institutionOfUser(input.attributes()),
                reason,
                triggeredBy,
                input.sessionCount(),
                true,
                markers,
                input.groups(),
                roles,
                input.namedOnClients().stream().distinct().toList());
    }

    /** What has completed so far, for rollback. */
    public record Progress(boolean disabled, List<GroupRef> groupsRemoved, List<String> rolesStripped,
                           List<String> grantsRemoved) {
        public Progress {
            groupsRemoved = List.copyOf(groupsRemoved);
            rolesStripped = List.copyOf(rolesStripped);
            grantsRemoved = List.copyOf(grantsRemoved);
        }

        public static Progress none() {
            return new Progress(false, List.of(), List.of(), List.of());
        }
    }

    /** The compensating actions after a failure. Sessions are never restored. */
    public record Rollback(boolean reEnable, List<GroupRef> groupsToReAdd, List<String> rolesToReAdd,
                           List<String> grantsToRestore) {
        public boolean isEmpty() {
            return !reEnable && groupsToReAdd.isEmpty() && rolesToReAdd.isEmpty() && grantsToRestore.isEmpty();
        }
    }

    public static Rollback rollback(Progress progress) {
        return new Rollback(progress.disabled(), new ArrayList<>(progress.groupsRemoved()),
                new ArrayList<>(progress.rolesStripped()), new ArrayList<>(progress.grantsRemoved()));
    }
}
