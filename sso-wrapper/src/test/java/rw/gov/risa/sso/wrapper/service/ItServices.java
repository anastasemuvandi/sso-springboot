package rw.gov.risa.sso.wrapper.service;

import static org.mockito.Mockito.mock;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessProperties;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyProperties;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/** Wires the shared services by hand against {@link KeycloakIT} (no Spring context). */
public final class ItServices {

    public final AuditEmitter audit = mock(AuditEmitter.class);
    public final KeycloakDirectory directory = new KeycloakDirectory();
    public final ClientAccessService access = new ClientAccessService(KeycloakIT.executors(), directory,
            new AccessPolicyStore(), new ClientAccessProperties(true), KeycloakIT.ssoProperties(), audit);
    public final InstitutionRegistryHolder registry;
    public final ClientScopeService scopes;
    public final JoinKeyService joinKeys;

    public ItServices() {
        DelegationProperties dp = new DelegationProperties("configs/delegated-permissions/templates.json",
                "configs/institutions.yaml", true, true, "institution", true);
        registry = new InstitutionRegistryHolder(dp);
        registry.reload();
        scopes = new ClientScopeService(KeycloakIT.executors(), directory, registry, access, audit);
        joinKeys = new JoinKeyService(KeycloakIT.executors(), directory, access, scopes,
                new JoinKeyProperties(true, false, true, 200), KeycloakIT.ssoProperties(), audit);
    }
}
