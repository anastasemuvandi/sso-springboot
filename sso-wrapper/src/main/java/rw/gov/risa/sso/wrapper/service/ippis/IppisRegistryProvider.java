package rw.gov.risa.sso.wrapper.service.ippis;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;

/**
 * The institution registry ({@code configs/institutions.yaml}, INSTITUTION_REGISTRY_CONFIG) as the
 * IPPIS feature reads it — the {@code ippis_codes -> slug} lookup — over the shared
 * {@link InstitutionRegistryHolder}. Never fails closed: a missing registry maps nothing, and the
 * sync then leaves the institution attribute alone rather than writing a raw code.
 */
@Component
public class IppisRegistryProvider {

    private final InstitutionRegistryHolder holder;
    private volatile InstitutionRegistry override;

    public IppisRegistryProvider(InstitutionRegistryHolder holder) {
        this.holder = holder;
    }

    public InstitutionRegistry registry() {
        InstitutionRegistry o = override;
        return o != null ? o : holder.current();
    }

    /** Test seam: pin a registry (null = back to the shared holder). */
    public void use(InstitutionRegistry registry) {
        this.override = registry;
    }
}
