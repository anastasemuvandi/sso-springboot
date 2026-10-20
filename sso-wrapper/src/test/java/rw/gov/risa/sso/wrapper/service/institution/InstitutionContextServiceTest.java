package rw.gov.risa.sso.wrapper.service.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerAuthentication;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class InstitutionContextServiceTest {

    AuditEmitter audit = mock(AuditEmitter.class);
    DelegatedPermissionsHolder holder = mock(DelegatedPermissionsHolder.class);

    static DelegationProperties props(boolean guardrail, boolean crosscheck) {
        return new DelegationProperties("configs/delegated-permissions/templates.json", "configs/institutions.yaml",
                guardrail, crosscheck, "institution", true);
    }

    InstitutionContextService service(boolean guardrail, boolean crosscheck) {
        var registry = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        when(holder.current()).thenReturn(registry);
        when(holder.grantLookup()).thenReturn(rw.gov.risa.sso.wrapper.policy.institutioncontext.GrantLookup.of(registry));
        return new InstitutionContextService(holder, props(guardrail, crosscheck), audit);
    }

    static CallerPrincipal admin(String slug, Map<String, Set<String>> clientRoles, String claim) {
        return TestCallers.caller(slug + ".admin", Set.of("inst:" + slug + ":admin"), clientRoles, claim);
    }

    @Test
    void resolvesTheSingleAdministeredInstitution() {
        InstitutionContext ctx = service(true, true).resolve(admin("minagri", Map.of(), "minagri"), null);
        assertThat(ctx.institution()).isEqualTo("minagri");
        assertThat(ctx.grant().has(Capability.MANAGE_CLIENTS)).isTrue();
        verify(audit, never()).emit(any(AuditEvent.class));
    }

    @Test
    void anotherInstitutionIs403() {
        assertThatThrownBy(() -> service(true, true).resolve(admin("minagri", Map.of(), null), "minisante"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("institution_not_administered"));
    }

    @Test
    void guardrailViolationIs403AndAudited() {
        CallerPrincipal c = admin("minagri", Map.of("realm-management", Set.of("view-users")), null);
        assertThatThrownBy(() -> service(true, true).resolve(c, null))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(403);
                    assertThat(((ApiException) e).error()).isEqualTo("guardrail_violation");
                });
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.DELEGATED_GUARDRAIL_VIOLATION
                && "denied".equals(e.outcome()) && "minagri".equals(e.extra().get("institution"))
                && e.extra().get("violations").toString().contains("view-users")));
    }

    @Test
    void guardrailNotEnforcedResolvesButStillAudits() {
        CallerPrincipal c = admin("minagri", Map.of("realm-management", Set.of("manage-users")), null);
        assertThat(service(false, true).resolve(c, null).institution()).isEqualTo("minagri");
        verify(audit).emit(argThat((AuditEvent e) -> "warning".equals(e.outcome())));
    }

    @Test
    void claimMismatchIs403UnlessCrosscheckIsOff() {
        assertThatThrownBy(() -> service(true, true).resolve(admin("minagri", Map.of(), "minisante"), null))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("institution_claim_mismatch"));
        assertThat(service(true, false).resolve(admin("minagri", Map.of(), "minisante"), null).institution())
                .isEqualTo("minagri");
    }

    @Test
    void customClaimNameIsHonoured() {
        var registry = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        when(holder.grantLookup()).thenReturn(rw.gov.risa.sso.wrapper.policy.institutioncontext.GrantLookup.of(registry));
        var svc = new InstitutionContextService(holder, new DelegationProperties("a", "b", true, true, "ministry", true),
                audit);
        CallerPrincipal c = TestCallers.caller("x", Set.of("inst:minagri:admin"), Map.of(), null);
        CallerPrincipal withClaim = new CallerPrincipal(c.userId(), c.username(), c.email(), c.name(), c.azp(),
                c.realmRoles(), c.clientRoles(), null, c.sessionId(), c.accessToken(), Map.of("ministry", "MINISANTE"));
        assertThat(svc.identity(withClaim).institutionClaim()).isEqualTo("minisante");
    }

    @Test
    void platformAdminMustNameAnInstitution() {
        var svc = service(true, true);
        CallerPrincipal sa = TestCallers.caller("p", Set.of("super_admin"), null);
        assertThatThrownBy(() -> svc.resolve(sa, null))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("institution_required"));
        assertThat(svc.resolve(sa, "minisante").institution()).isEqualTo("minisante");
    }

    @Test
    void missingTemplatesAre503DelegationUnavailable() {
        when(holder.grantLookup()).thenThrow(new DelegatedPermissionConfigException("file not found"));
        var svc = new InstitutionContextService(holder, props(true, true), audit);
        assertThatThrownBy(() -> svc.resolve(admin("minagri", Map.of(), null), null))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(503);
                    assertThat(((ApiException) e).error()).isEqualTo("delegation_unavailable");
                });
    }

    @Test
    void requireCapabilityRefusesWhatTheTierLacks() {
        var svc = service(true, true);
        InstitutionContext hr = svc.resolve(TestCallers.caller("h", Set.of("inst:minagri:hr"), null), null);
        assertThatThrownBy(() -> InstitutionContextService.requireCapability(hr, Capability.MANAGE_CLIENTS))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("capability_denied"));
        assertThatThrownBy(() -> svc.resolve(TestCallers.caller("h", Set.of("inst:minagri:hr"), null), null,
                Capability.VIEW_CLIENTS)).isInstanceOf(ApiException.class);
    }

    @SuppressWarnings("unused")
    void handler(@CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx, InstitutionContext plain) {
    }

    @Test
    void argumentResolverResolvesOrRefuses() throws Exception {
        var svc = service(true, true);
        var resolver = new InstitutionWebConfig.InstitutionContextArgumentResolver(svc);
        var m = getClass().getDeclaredMethod("handler", InstitutionContext.class, InstitutionContext.class);
        MethodParameter annotated = new MethodParameter(m, 0);
        assertThat(resolver.supportsParameter(annotated)).isTrue();
        assertThat(resolver.supportsParameter(new MethodParameter(m, 1))).isFalse();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("institution", "minagri");
        try {
            SecurityContextHolder.getContext().setAuthentication(new CallerAuthentication(admin("minagri", Map.of(), null)));
            Object ctx = resolver.resolveArgument(annotated, null, new ServletWebRequest(req), null);
            assertThat(((InstitutionContext) ctx).institution()).isEqualTo("minagri");
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> resolver.resolveArgument(annotated, null, new ServletWebRequest(req), null))
                    .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(401));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void interceptorFailsClosedWith503() {
        when(holder.available()).thenReturn(false);
        when(holder.unavailableReason()).thenReturn(java.util.Optional.of("missing"));
        var i = new InstitutionWebConfig.DelegationAvailableInterceptor(holder);
        assertThatThrownBy(() -> i.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("delegation_unavailable"));
        when(holder.available()).thenReturn(true);
        assertThat(i.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void holderBootsWithoutTheFileAndReloadKeepsTheOldRegistryOnError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("templates.json");
        DelegatedPermissionsHolder h = new DelegatedPermissionsHolder(new DelegationProperties(file.toString(),
                "x", true, true, "institution", true), java.time.Clock.systemUTC());
        h.loadAtStartup();
        assertThat(h.available()).isFalse();
        assertThat(h.unavailableReason()).hasValueSatisfying(r -> assertThat(r).contains("not found"));

        Files.copy(KeycloakIT.locate("configs/delegated-permissions/templates.json"), file);
        assertThat(h.reload().templates()).containsKey("institution-admin");
        assertThat(h.available()).isTrue();

        Files.writeString(file, "{ not json");
        assertThatThrownBy(h::reload).isInstanceOf(DelegatedPermissionConfigException.class);
        assertThat(h.current().templates()).containsKey("institution-admin");
    }

    @Test
    void unsafeTemplatesAreRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("templates.json");
        String json = Files.readString(KeycloakIT.locate("configs/delegated-permissions/templates.json"))
                .replace("\"allow\": [\"inst:{slug}:*\"]", "\"allow\": [\"*\"]");
        Files.writeString(file, json);
        DelegatedPermissionsHolder h = new DelegatedPermissionsHolder(new DelegationProperties(file.toString(),
                "x", true, true, "institution", true), java.time.Clock.systemUTC());
        h.loadAtStartup();
        assertThat(h.available()).isFalse();
    }

    @Test
    void delegatedExecutionPicksTheCredential() {
        var executors = mock(rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors.class);
        var sa = rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec.of(null);
        var asCaller = rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec.of(null, true);
        when(executors.serviceAccount()).thenReturn(sa);
        when(executors.asCaller(any(String.class))).thenReturn(asCaller);
        CallerPrincipal c = admin("minagri", Map.of(), null);
        var on = new DelegatedExecution(executors, props(true, true));
        assertThat(on.forCaller(c)).isSameAs(asCaller);
        assertThat(on.forClientPolicies(c, true)).isSameAs(sa);
        var off = new DelegatedExecution(executors, new DelegationProperties("a", "b", true, true, "institution", false));
        assertThat(off.forCaller(c)).isSameAs(sa);
        assertThat(List.of(on.executeAsCaller(), off.executeAsCaller())).containsExactly(true, false);
    }
}
