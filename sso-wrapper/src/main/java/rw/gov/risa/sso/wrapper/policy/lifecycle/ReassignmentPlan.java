package rw.gov.risa.sso.wrapper.policy.lifecycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code reassign_access}'s pure part: an active account only (a disabled one must go through
 * terminate — {@code account_disabled}); group names resolved to ids, unknown names recorded
 * as {@code groupsNotFound}, never fatal.
 */
public record ReassignmentPlan(Map<String, String> remove, Map<String, String> add, List<String> groupsNotFound) {

    public static final String ERROR_USER_NOT_FOUND = "user_not_found";
    public static final String ERROR_ACCOUNT_DISABLED = "account_disabled";

    public ReassignmentPlan {
        remove = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(remove));
        add = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(add));
        groupsNotFound = List.copyOf(groupsNotFound);
    }

    /** Thrown-free validation: the error code, or null when the account may be reassigned. */
    public static String refusal(boolean userExists, Boolean enabled) {
        if (!userExists) {
            return ERROR_USER_NOT_FOUND;
        }
        return Boolean.FALSE.equals(enabled) ? ERROR_ACCOUNT_DISABLED : null;
    }

    /** @param groupIdByName the result of looking each name up (absent → not found) */
    public static ReassignmentPlan plan(List<String> removeGroups, List<String> addGroups,
                                        Map<String, String> groupIdByName) {
        Map<String, String> remove = new LinkedHashMap<>();
        Map<String, String> add = new LinkedHashMap<>();
        List<String> notFound = new ArrayList<>();
        for (String g : removeGroups) {
            String id = groupIdByName.get(g);
            if (id != null) {
                remove.put(g, id);
            } else {
                notFound.add(g);
            }
        }
        for (String g : addGroups) {
            String id = groupIdByName.get(g);
            if (id != null) {
                add.put(g, id);
            } else {
                notFound.add(g);
            }
        }
        return new ReassignmentPlan(remove, add, notFound);
    }
}
