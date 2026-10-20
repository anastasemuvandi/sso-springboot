package rw.gov.risa.sso.wrapper.policy.lifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What offboarding one account removes. Built by {@link OffboardingPlanner#plan}; executed in
 * {@link OffboardingStep} order by the service; audited with {@link #auditExtra}.
 */
public record OffboardingPlan(
        String userId,
        String username,
        String institution,
        String reason,
        String triggeredBy,
        int sessionsToKill,
        boolean disable,
        Map<String, List<String>> markerAttributes,
        List<GroupRef> groupsToRemove,
        List<String> rolesToStrip,
        List<String> clientsToUnname) {

    public OffboardingPlan {
        markerAttributes = Map.copyOf(markerAttributes);
        groupsToRemove = List.copyOf(groupsToRemove);
        rolesToStrip = List.copyOf(rolesToStrip);
        clientsToUnname = List.copyOf(clientsToUnname);
    }

    /**
     * Audit {@code extra} for {@code lifecycle.offboard} (outcome success): target, institution
     * tag (omitted when unknown), reason, and what was removed.
     */
    public Map<String, Object> auditExtra() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("user_id", userId);
        extra.put("target_user", userId);
        extra.put("target_username", username);
        if (institution != null) {
            extra.put("institution", institution);
        }
        extra.put("reason", reason);
        extra.put("sessions_killed", sessionsToKill);
        extra.put("groups_removed", groupsToRemove.stream().map(GroupRef::name).toList());
        extra.put("roles_removed", rolesToStrip);
        extra.put("client_grants_removed", clientsToUnname);
        return extra;
    }

    /** Audit {@code extra} for a failed, rolled-back offboarding (error truncated to 300 chars). */
    public Map<String, Object> failureAuditExtra(String error) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("user_id", userId);
        extra.put("target_user", userId);
        extra.put("target_username", username);
        if (institution != null) {
            extra.put("institution", institution);
        }
        extra.put("reason", reason);
        String e = error == null ? "" : error;
        extra.put("error", e.length() > 300 ? e.substring(0, 300) : e);
        extra.put("rolled_back", true);
        return extra;
    }
}
