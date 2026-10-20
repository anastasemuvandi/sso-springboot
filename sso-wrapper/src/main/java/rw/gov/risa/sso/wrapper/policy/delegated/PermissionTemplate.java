package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reusable delegation tier, parameterised by {@code {slug}}.
 *
 * @param name            template key in templates.json (e.g. {@code institution-admin})
 * @param grantRole       e.g. {@code inst:{slug}:admin} — holding it IS the grant
 * @param group           member group, {@code /{slug}} or a subgroup of it
 * @param provisionRoles  short names rendered as {@code inst:<slug>:<name>}
 */
public record PermissionTemplate(
        String name,
        String displayName,
        String description,
        String grantRole,
        Set<Capability> capabilities,
        String group,
        boolean includeSubgroups,
        List<String> roleAllow,
        List<String> roleDeny,
        List<String> clientAllow,
        List<String> clientDeny,
        List<String> provisionRoles) {

    public PermissionTemplate {
        capabilities = capabilities.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Capability.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        roleAllow = List.copyOf(roleAllow);
        roleDeny = List.copyOf(roleDeny);
        clientAllow = List.copyOf(clientAllow);
        clientDeny = List.copyOf(clientDeny);
        provisionRoles = List.copyOf(provisionRoles);
    }

    private Pattern grantRoleRegex() {
        String literal = Pattern.quote(grantRole);
        // Pattern.quote wraps in \Q...\E; splice the capture group in for each placeholder.
        String capture = "\\E(" + DelegatedNamespaces.SLUG_CHARS + ")\\Q";
        return Pattern.compile("\\A" + literal.replace(DelegatedNamespaces.SLUG_PLACEHOLDER, capture) + "\\z");
    }

    /** The institution this role grants this template over, if any ({@code inst:minagri:admin -> minagri}). */
    public Optional<String> slugForRole(String roleName) {
        if (roleName == null) {
            return Optional.empty();
        }
        Matcher m = grantRoleRegex().matcher(roleName);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The concrete realm role that confers this tier over {@code slug}. */
    public String grantRoleFor(String slug) {
        return DelegatedNamespaces.render(grantRole, slug);
    }

    /** This template rendered for one institution. */
    public ResolvedGrant render(String slug) {
        return new ResolvedGrant(
                slug,
                List.of(name),
                capabilities,
                DelegatedNamespaces.render(group, slug),
                includeSubgroups,
                renderAll(roleAllow, slug),
                renderAll(roleDeny, slug),
                renderAll(clientAllow, slug),
                renderAll(clientDeny, slug));
    }

    /** Realm roles that must exist for this tier — grant role first, deduplicated. */
    public List<String> rolesToProvision(String slug) {
        Set<String> out = new LinkedHashSet<>();
        out.add(grantRoleFor(slug));
        for (String shortName : provisionRoles) {
            out.add("inst:" + slug + ":" + shortName);
        }
        return List.copyOf(out);
    }

    private static List<String> renderAll(List<String> patterns, String slug) {
        List<String> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            out.add(DelegatedNamespaces.render(p, slug));
        }
        return out;
    }
}
