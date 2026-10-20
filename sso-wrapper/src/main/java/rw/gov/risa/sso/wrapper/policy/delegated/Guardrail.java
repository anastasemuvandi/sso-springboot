package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Roles that are incompatible with holding a delegated institution grant.
 *
 * <p>A broad role reaches Keycloak directly, so it bypasses every scoped check — holding
 * one turns a "local" admin into a realm-wide one. The rule is absolute.
 *
 * @param forbiddenRealmRoles  exact realm-role names
 * @param forbiddenClientRoles clientId -> glob patterns; {@code ["*"]} = no role on that client at all
 */
public record Guardrail(Set<String> forbiddenRealmRoles, Map<String, List<String>> forbiddenClientRoles) {

    public Guardrail {
        forbiddenRealmRoles = Collections.unmodifiableSet(new TreeSet<>(forbiddenRealmRoles));
        Map<String, List<String>> copy = new LinkedHashMap<>();
        forbiddenClientRoles.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        forbiddenClientRoles = Collections.unmodifiableMap(copy);
    }

    public boolean isForbiddenRealmRole(String role) {
        return forbiddenRealmRoles.contains(role);
    }

    public boolean isForbiddenClientRole(String clientId, String role) {
        return Glob.matchesAny(role, forbiddenClientRoles.getOrDefault(clientId, List.of()));
    }

    /** Every broad-admin grant in the realm-role set (empty = clean). */
    public List<GuardrailViolation> violations(Collection<String> realmRoles) {
        return violations(realmRoles, Map.of());
    }

    /**
     * Every broad-admin grant present in the given role set (empty = clean). Realm roles
     * first (sorted, deduplicated), then client roles by clientId then role name.
     *
     * @param realmRoles  the caller's (effective) realm roles
     * @param clientRoles clientId -> role names (e.g. the token's {@code resource_access}); may be null
     */
    public List<GuardrailViolation> violations(
            Collection<String> realmRoles, Map<String, ? extends Collection<String>> clientRoles) {
        List<GuardrailViolation> found = new ArrayList<>();
        for (String role : new TreeSet<>(realmRoles == null ? List.of() : realmRoles)) {
            if (isForbiddenRealmRole(role)) {
                found.add(GuardrailViolation.realmRole(role));
            }
        }
        if (clientRoles != null) {
            for (Map.Entry<String, ? extends Collection<String>> e : new TreeMap<>(clientRoles).entrySet()) {
                for (String role : new TreeSet<>(e.getValue() == null ? List.of() : e.getValue())) {
                    if (isForbiddenClientRole(e.getKey(), role)) {
                        found.add(GuardrailViolation.clientRole(e.getKey(), role));
                    }
                }
            }
        }
        return List.copyOf(found);
    }
}
