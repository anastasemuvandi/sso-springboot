package rw.gov.risa.sso.wrapper.api.delegated;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** {@code /delegated-permissions}: review surface, preview, apply (dry run + audit), audit report, gates. */
@WrapperTest
class DelegatedPermissionsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;

    RealmResource realm;

    static RoleRepresentation role(String name) {
        RoleRepresentation r = new RoleRepresentation();
        r.setName(name);
        return r;
    }

    @BeforeEach
    void realm() {
        realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
        when(realm.roles().list()).thenReturn(List.of(role("inst:minagri:admin"), role("inst:minagri:hr"),
                role("super_admin")));
    }

    @Test
    void describeAndPreviewForPlatformAdmins() throws Exception {
        mvc.perform(get("/delegated-permissions").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").exists());
        mvc.perform(get("/delegated-permissions/institutions/minagri").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("minagri"))
                .andExpect(jsonPath("$.templates[0].grant_role").value("inst:minagri:admin"))
                .andExpect(jsonPath("$.effective_grant").exists());
    }

    @Test
    void anInvalidSlugIsA400() throws Exception {
        mvc.perform(get("/delegated-permissions/institutions/MINAGRI!").with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void institutionAdminsAreRefused() throws Exception {
        mvc.perform(get("/delegated-permissions").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/delegated-permissions/apply").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"institutions\":[\"minagri\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dryRunPlansWithoutWriting() throws Exception {
        mvc.perform(post("/delegated-permissions/apply").with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"institutions\":[\"minagri\"],\"dry_run\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dry_run").value(true))
                .andExpect(jsonPath("$.created").isEmpty())
                .andExpect(jsonPath("$.unchanged[0]").value("inst:minagri:admin"))
                .andExpect(jsonPath("$.plan[?(@.role=='inst:minagri:staff')].action").value("create"));
        verify(realm.roles(), never()).create(any());
        verify(audit, never()).emit(any(AuditEvent.class));
    }

    @Test
    void applyCreatesTheMissingRolesAndIsAudited() throws Exception {
        mvc.perform(post("/delegated-permissions/apply").with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"institutions\":[\"minagri\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(org.hamcrest.Matchers.hasItem("inst:minagri:staff")));
        verify(realm.roles()).create(argThat(r -> "inst:minagri:staff".equals(r.getName())));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.DELEGATED_APPLIED
                && "platform.super".equals(e.actor())));
    }

    @Test
    void applyNeedsAtLeastOneInstitution() throws Exception {
        mvc.perform(post("/delegated-permissions/apply").with(TestCallers.superAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"institutions\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void auditReportsMissingRolesAndViolations() throws Exception {
        UserRepresentation holder = new UserRepresentation();
        holder.setId("u1");
        holder.setUsername("minagri.admin");
        when(realm.roles().get("inst:minagri:admin").getUserMembers(anyInt(), anyInt())).thenReturn(List.of(holder));
        when(realm.users().get("u1").roles().realmLevel().listEffective())
                .thenReturn(List.of(role("inst:minagri:admin"), role("super_admin")));
        when(realm.clients().findByClientId(anyString())).thenReturn(List.of());
        mvc.perform(get("/delegated-permissions/audit").param("institution", "minagri").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.guardrail_violations[0].username").value("minagri.admin"))
                .andExpect(jsonPath("$.guardrail_violations[0].forbidden[0].role").value("super_admin"))
                .andExpect(jsonPath("$.missing_roles[?(@.role=='inst:minagri:staff')]").exists())
                .andExpect(jsonPath("$.realm").value("government-internal"));
    }

    @Test
    void reloadReReadsTheFile() throws Exception {
        mvc.perform(post("/delegated-permissions/reload").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reloaded"));
    }
}
