package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.Collection;
import java.util.Map;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.Guardrail;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;

/**
 * The loaded delegated-permission templates, as the resolver needs them. Implemented by an
 * adapter over the delegated registry; tests supply fakes.
 */
public interface GrantLookup {

    /** {slug: merged grant} for the caller's realm roles. */
    Map<String, ResolvedGrant> resolveCaller(Collection<String> realmRoles);

    /**
     * Everything an institution's templates define (platform admin acting for it).
     *
     * @throws DelegatedPermissionConfigException when the slug has no usable templates
     */
    ResolvedGrant grantFor(String slug);

    Guardrail guardrail();

    /** Adapter over the loaded delegated registry. */
    static GrantLookup of(DelegatedPermissionRegistry registry) {
        return new GrantLookup() {
            @Override
            public Map<String, ResolvedGrant> resolveCaller(Collection<String> realmRoles) {
                return registry.resolveCaller(realmRoles);
            }

            @Override
            public ResolvedGrant grantFor(String slug) {
                return registry.grantFor(slug);
            }

            @Override
            public Guardrail guardrail() {
                return registry.guardrail();
            }
        };
    }
}
