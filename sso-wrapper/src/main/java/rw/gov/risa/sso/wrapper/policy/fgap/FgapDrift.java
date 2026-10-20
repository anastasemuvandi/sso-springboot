package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import rw.gov.risa.sso.wrapper.policy.delegated.PyRepr;

/**
 * Drift — a desired plan vs what the realm actually has, compared by object name. Anything
 * named {@code inst:*} the plan does not contain is reported as unmanaged ("made by hand in
 * the console").
 */
public final class FgapDrift {

    private FgapDrift() {
    }

    public enum Kind {
        MISSING_POLICY("missing-policy"),
        MISSING_PERMISSION("missing-permission"),
        MISMATCH("mismatch"),
        UNMANAGED("unmanaged");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** One difference between the templates and the realm. */
    public record Finding(Kind kind, String name, String detail) {
        @Override
        public String toString() {
            return "[" + kind.wire() + "] " + name + ": " + detail;
        }
    }

    /** The managed fields of a role policy as read from the realm. */
    public record ActualPolicy(List<String> roles) {
        public ActualPolicy {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /** The managed fields of a scope permission as read from the realm. */
    public record ActualPermission(List<String> resources, List<String> scopes, List<String> policies) {
        public ActualPermission {
            resources = resources == null ? List.of() : List.copyOf(resources);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            policies = policies == null ? List.of() : List.copyOf(policies);
        }
    }

    private static List<Finding> compare(String name, String field, List<String> want, List<String> got) {
        if (want.stream().sorted().toList().equals(got.stream().sorted().toList())) {
            return List.of();
        }
        TreeSet<String> missing = new TreeSet<>(want);
        missing.removeAll(got);
        TreeSet<String> extra = new TreeSet<>(got);
        extra.removeAll(want);
        List<String> bits = new ArrayList<>();
        if (!missing.isEmpty()) {
            bits.add("missing " + PyRepr.list(missing));
        }
        if (!extra.isEmpty()) {
            bits.add("unexpected " + PyRepr.list(extra));
        }
        return List.of(new Finding(Kind.MISMATCH, name, field + ": " + String.join("; ", bits)));
    }

    public static List<Finding> diff(Collection<InstitutionPlan> plans, Map<String, ActualPolicy> actualPolicies,
                                     Map<String, ActualPermission> actualPermissions) {
        List<Finding> findings = new ArrayList<>();
        Map<String, RolePolicy> wantedPolicies = new TreeMap<>();
        Map<String, ScopePermission> wantedPermissions = new TreeMap<>();
        for (InstitutionPlan plan : plans) {
            plan.policies().forEach(p -> wantedPolicies.put(p.name(), p));
            plan.permissions().forEach(p -> wantedPermissions.put(p.name(), p));
        }

        for (Map.Entry<String, RolePolicy> e : wantedPolicies.entrySet()) {
            ActualPolicy actual = actualPolicies.get(e.getKey());
            if (actual == null) {
                findings.add(new Finding(Kind.MISSING_POLICY, e.getKey(),
                        "no role policy for " + PyRepr.list(e.getValue().roles())));
                continue;
            }
            findings.addAll(compare(e.getKey(), "roles", e.getValue().roles(), actual.roles()));
        }
        for (Map.Entry<String, ScopePermission> e : wantedPermissions.entrySet()) {
            ScopePermission p = e.getValue();
            ActualPermission actual = actualPermissions.get(e.getKey());
            if (actual == null) {
                findings.add(new Finding(Kind.MISSING_PERMISSION, e.getKey(), p.resourceType().wire() + " "
                        + PyRepr.list(p.scopes()) + " over " + p.resources().size() + " resource(s)"));
                continue;
            }
            findings.addAll(compare(e.getKey(), "scopes", p.scopes(), actual.scopes()));
            findings.addAll(compare(e.getKey(), "resources", p.resources(), actual.resources()));
            findings.addAll(compare(e.getKey(), "policies", p.policies(), actual.policies()));
        }
        for (String name : new TreeSet<>(actualPolicies.keySet())) {
            if (!wantedPolicies.containsKey(name) && FgapProjection.isManagedName(name)) {
                findings.add(new Finding(Kind.UNMANAGED, name,
                        "policy is named as managed but no template produces it"));
            }
        }
        for (String name : new TreeSet<>(actualPermissions.keySet())) {
            if (!wantedPermissions.containsKey(name) && FgapProjection.isManagedName(name)) {
                findings.add(new Finding(Kind.UNMANAGED, name,
                        "permission is named as managed but no template produces it"));
            }
        }
        return List.copyOf(findings);
    }
}
