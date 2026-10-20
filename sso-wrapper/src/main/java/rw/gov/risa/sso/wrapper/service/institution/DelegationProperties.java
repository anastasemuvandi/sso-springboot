package rw.gov.risa.sso.wrapper.service.institution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Delegated (institution) administration settings — env names of the reference.
 *
 * @param permissionsConfig         DELEGATED_PERMISSIONS_CONFIG (relative: working dir, then its parent, then /app)
 * @param institutionRegistryConfig INSTITUTION_REGISTRY_CONFIG (same resolution)
 * @param guardrailEnforce          DELEGATED_GUARDRAIL_ENFORCE: refuse (403) a grant holder who also holds a broad admin role
 * @param claimCrosscheck           DELEGATED_CLAIM_CROSSCHECK: refuse (403) when the institution claim contradicts the grant role
 * @param institutionClaim          DELEGATED_INSTITUTION_CLAIM: the token claim that corroborates the institution
 * @param executeAsCaller           DELEGATED_EXECUTE_AS_CALLER: delegated Keycloak calls use the caller's own token
 */
@ConfigurationProperties(prefix = "sso.delegated")
public record DelegationProperties(
        @DefaultValue("configs/delegated-permissions/templates.json") String permissionsConfig,
        @DefaultValue("configs/institutions.yaml") String institutionRegistryConfig,
        @DefaultValue("true") boolean guardrailEnforce,
        @DefaultValue("true") boolean claimCrosscheck,
        @DefaultValue("institution") String institutionClaim,
        @DefaultValue("true") boolean executeAsCaller) {
}
