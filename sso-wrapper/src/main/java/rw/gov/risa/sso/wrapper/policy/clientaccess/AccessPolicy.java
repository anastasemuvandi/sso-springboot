package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Keycloak authorization policy, reduced to the fields the access rule reads. Adapt a
 * {@code PolicyRepresentation} into this: {@code config.users} is Keycloak's JSON-encoded list
 * of user ids, {@code config.groups} a JSON list of {@code {"id":..., "extendChildren":...}}.
 * {@code logic} null means {@code POSITIVE}.
 */
public record AccessPolicy(String id, String name, String type, String logic, Map<String, String> config) {

    public static final String TYPE_USER = "user";
    public static final String TYPE_GROUP = "group";
    public static final String LOGIC_POSITIVE = "POSITIVE";
    public static final String LOGIC_NEGATIVE = "NEGATIVE";

    public AccessPolicy {
        config = config == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public boolean isNegative() {
        return LOGIC_NEGATIVE.equals(logic == null ? LOGIC_POSITIVE : logic);
    }

    public boolean isUserPolicy() {
        return TYPE_USER.equals(type);
    }

    public boolean isGroupPolicy() {
        return TYPE_GROUP.equals(type);
    }

    /** A user policy with a well-formed {@code config.users}. */
    public static AccessPolicy user(String id, String name, boolean negative, List<String> userIds) {
        return new AccessPolicy(id, name, TYPE_USER, negative ? LOGIC_NEGATIVE : LOGIC_POSITIVE,
                Map.of("users", PolicyIds.encodeUsers(userIds)));
    }

    /** A group policy with a well-formed {@code config.groups} ({@code extendChildren:false}). */
    public static AccessPolicy group(String id, String name, boolean negative, List<String> groupIds) {
        return new AccessPolicy(id, name, TYPE_GROUP, negative ? LOGIC_NEGATIVE : LOGIC_POSITIVE,
                Map.of("groups", PolicyIds.encodeGroups(groupIds)));
    }
}
