package rw.gov.risa.sso.wrapper.service.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** A deep-stubbed realm behind a real {@link PlatformSupport} (scope service mocked, audit captured). */
final class PlatformMocks {

    final RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    final KeycloakExecutors executors = mock(KeycloakExecutors.class);
    final KeycloakDirectory directory = mock(KeycloakDirectory.class);
    final ClientScopeService scopes = mock(ClientScopeService.class);
    final AuditEmitter audit = mock(AuditEmitter.class);
    final PlatformSupport support;

    static final CallerPrincipal SUPER = TestCallers.caller("platform.super", Set.of("super_admin"), null);
    static final CallerPrincipal ADMIN = TestCallers.caller("platform.admin", Set.of("admin"), null);

    PlatformMocks() {
        KeycloakExec exec = KeycloakExec.of(realm);
        lenient().when(executors.serviceAccount()).thenReturn(exec);
        lenient().when(scopes.knownSlugs()).thenReturn(Set.of("minagri", "minisante"));
        lenient().when(scopes.scopeOf(any())).thenAnswer(inv -> {
            ClientRepresentation c = inv.getArgument(0);
            return ClientScope.parse(c.getClientId(), c.getAttributes(), Set.of("minagri", "minisante"));
        });
        support = new PlatformSupport(executors, directory, scopes, audit);
    }

    static ClientRepresentation client(String clientId, Map<String, String> attributes) {
        ClientRepresentation c = new ClientRepresentation();
        c.setId("uuid-" + clientId);
        c.setClientId(clientId);
        c.setName(clientId);
        c.setAttributes(new java.util.HashMap<>(attributes));
        c.setPublicClient(false);
        return c;
    }

    /** {@code AuditEvent} built from a builder handed to {@link AuditEmitter#emit(AuditEvent.Builder)}. */
    static AuditEvent built(Object arg) {
        return arg instanceof AuditEvent.Builder b ? b.build() : (AuditEvent) arg;
    }
}
