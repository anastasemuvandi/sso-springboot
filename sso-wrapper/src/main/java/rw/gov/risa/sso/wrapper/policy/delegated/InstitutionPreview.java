package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.List;

/**
 * One institution's effective grant plus the roles each enabled tier needs
 * ({@code DelegatedPermissionsService.preview}, {@code GET /delegated-permissions/institutions/{slug}}).
 *
 * @param templates      the enabled tiers, in assignment order (withheld tiers are absent)
 * @param effectiveGrant the union of the tiers — the institution's ceiling
 */
public record InstitutionPreview(String institution, List<Tier> templates, ResolvedGrant effectiveGrant) {

    /**
     * @param grantRole    the rendered grant role, e.g. {@code inst:minagri:admin}
     * @param capabilities wire names, sorted
     * @param realmRoles   {@link PermissionTemplate#rolesToProvision(String)}
     */
    public record Tier(String name, String displayName, String grantRole, List<String> capabilities,
                       List<String> realmRoles) {
        public Tier {
            capabilities = List.copyOf(capabilities);
            realmRoles = List.copyOf(realmRoles);
        }
    }

    public InstitutionPreview {
        templates = List.copyOf(templates);
    }

    /**
     * Build the preview.
     *
     * @throws DelegatedPermissionConfigException for an invalid slug or an institution
     *         whose tiers are all withheld (callers surface that as 400)
     */
    public static InstitutionPreview of(DelegatedPermissionRegistry registry, String slug) {
        ResolvedGrant effective = registry.grantFor(slug);
        List<Tier> tiers = registry.enabledTemplates(slug).stream()
                .map(t -> new Tier(t.name(), t.displayName(), t.grantRoleFor(slug),
                        t.capabilities().stream().map(Capability::wire).sorted().toList(),
                        t.rolesToProvision(slug)))
                .toList();
        return new InstitutionPreview(slug, tiers, effective);
    }
}
