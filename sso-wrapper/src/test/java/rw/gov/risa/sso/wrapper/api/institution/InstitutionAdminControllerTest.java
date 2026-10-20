package rw.gov.risa.sso.wrapper.api.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.policy.overview.LoginEvent;
import rw.gov.risa.sso.wrapper.policy.overview.BruteForceStatus;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** Own-institution behaviour of the context, users, roles, overview and audit routes. */
class InstitutionAdminControllerTest extends InstitutionAdminMvcSupport {

    // ---- context ---------------------------------------------------------------------------

    @Test
    void meReportsTheResolvedInstitutionAndCapabilities() throws Exception {
        mvc.perform(get("/institution-admin/me").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value(A))
                .andExpect(jsonPath("$.administered[0]").value(A))
                .andExpect(jsonPath("$.is_platform_admin").value(false))
                .andExpect(jsonPath("$.actor").value("minagri.admin"))
                .andExpect(jsonPath("$.capabilities", hasItem("manage-clients")));
    }

    @Test
    void permissionsDescribeOnlyTheCallersGrant() throws Exception {
        mvc.perform(get("/institution-admin/permissions").with(TestCallers.institutionAdmin(A, "hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value(A))
                .andExpect(jsonPath("$.members.group").value("/minagri"))
                .andExpect(jsonPath("$.roles.allow", containsInAnyOrder("inst:minagri:staff")))
                .andExpect(jsonPath("$.capabilities", not(hasItem("view-clients"))));
    }

    @Test
    void unauthenticatedIs401AndAPlainUserIs403() throws Exception {
        mvc.perform(get("/institution-admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/institution-admin/me").with(TestCallers.plainUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_institution_admin"));
    }

    @Test
    void aPlatformAdminMustNameTheInstitutionAndThenActsWithinIt() throws Exception {
        mvc.perform(get("/institution-admin/me").with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("institution_required"));
        mvc.perform(get("/institution-admin/users").param("institution", A).with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", containsInAnyOrder("alice", "andre", "amani")));
    }

    @Test
    void aPlatformAdminGetsTheInstitutionsCeilingNotTheirOwn() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").param("institution", A).with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"super_admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("role_not_assignable"));
        verify(kc, never()).addRealmRoles(any(), anyString(), any());
    }

    // ---- users -----------------------------------------------------------------------------

    @Test
    void listUsersReturnsTheInstitutionIncludingDepartmentsAsTheCaller() throws Exception {
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-a1", "u-a2", "u-a3")));
        ArgumentCaptor<KeycloakExec> exec = ArgumentCaptor.forClass(KeycloakExec.class);
        verify(kc, org.mockito.Mockito.atLeastOnce()).groupMembers(exec.capture(), anyString());
        assertThat(exec.getAllValues()).allMatch(KeycloakExec::asCaller);
    }

    @Test
    void searchFiltersWithinTheInstitution() throws Exception {
        mvc.perform(get("/institution-admin/users").param("search", "ali").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-a1")));
    }

    @Test
    void getOwnUser() throws Exception {
        mvc.perform(get("/institution-admin/users/u-a3").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("amani"))
                .andExpect(jsonPath("$.agencies[0].path").value("/minagri/planning"));
    }

    @Test
    void createUserIsPinnedToTheInstitutionAndAudited() throws Exception {
        mvc.perform(post("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"email\":\"newbie@minagri.gov.rw\",\"firstName\":\"New\","
                                + "\"lastName\":\"Bie\",\"national_id\":\"1199080012345678\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributes.institution[0]").value(A))
                .andExpect(jsonPath("$.attributes.national_id[0]").value("1199080012345678"))
                .andExpect(jsonPath("$.agencies[0].path").value("/minagri"));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_USER_CREATED
                && A.equals(e.extra().get("institution")) && "minagri.admin".equals(e.actor())));
        verify(joinKeys).validateIdentifierWrite(eq(true), eq("newbie@minagri.gov.rw"), any(), eq(null));
    }

    @Test
    void createUserNeedsManageMembership() throws Exception {
        // the auditor tier has neither manage-members nor manage-membership
        mvc.perform(post("/institution-admin/users").with(TestCallers.institutionAdmin(A, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newbie\",\"email\":\"n@x.rw\",\"firstName\":\"N\",\"lastName\":\"B\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
        verify(kc, never()).createUser(any(), any());
    }

    @Test
    void createUserValidatesTheBody() throws Exception {
        mvc.perform(post("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\",\"email\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void updateCannotMoveAUserOutOfTheInstitution() throws Exception {
        mvc.perform(patch("/institution-admin/users/u-a1").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Alicia\",\"phone\":\"+250788000111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alicia"))
                .andExpect(jsonPath("$.attributes.institution[0]").value(A))
                .andExpect(jsonPath("$.attributes.phone[0]").value("+250788000111"));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_USER_UPDATED
                && "u-a1".equals(e.extra().get("target_user"))));
    }

    @Test
    void deactivateDisablesAndEndsSessionsAsTheCaller() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a2/deactivate").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verify(kc).logout(argThat(KeycloakExec::asCaller), eq("u-a2"));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_USER_DISABLED));
    }

    @Test
    void anHrTierCannotDeactivateWithoutManageMembersButCanView() throws Exception {
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "hr")))
                .andExpect(status().isOk());
        mvc.perform(post("/institution-admin/users/u-a2/deactivate").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
    }

    // ---- roles -----------------------------------------------------------------------------

    @Test
    void assignableRolesAreTheOwnNamespaceMinusDenied() throws Exception {
        mvc.perform(get("/institution-admin/roles").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", not(hasItem("inst:minagri:admin"))))
                .andExpect(jsonPath("$[*].name", hasItem("inst:minagri:staff")))
                .andExpect(jsonPath("$[*].name", not(hasItem("inst:minisante:staff"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("super_admin"))));
    }

    @Test
    void assignAndRevokeAnOwnRoleAsTheCaller() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:staff\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("assigned"));
        verify(kc).addRealmRoles(argThat(KeycloakExec::asCaller), eq("u-a1"),
                argThat(l -> l.size() == 1 && "inst:minagri:staff".equals(l.get(0).getName())));
        mvc.perform(delete("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:staff\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_ROLE_REMOVED
                && "inst:minagri:staff".equals(e.extra().get("role"))));
    }

    @Test
    void aBroadOrCoAdminRoleIsNeverAssignable() throws Exception {
        for (String role : List.of("admin", "super_admin", "inst:minagri:admin", "mfa-exempt")) {
            mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("role_not_assignable"));
        }
        verify(kc, never()).addRealmRoles(any(), anyString(), any());
    }

    @Test
    void anUnknownRoleIs404OnlyAfterTheScopeCheck() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAuditorCannotMapRoles() throws Exception {
        mvc.perform(get("/institution-admin/roles").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
    }

    // ---- overview --------------------------------------------------------------------------

    @Test
    void overviewHasFourIndependentSectionsScopedToTheInstitution() throws Exception {
        long now = System.currentTimeMillis();
        when(kc.loginEvents(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new LoginEvent("LOGIN", "u-a1", now - 1000, null),
                        new LoginEvent("LOGIN_ERROR", "u-a2", now - 1000, "invalid_user_credentials"),
                        new LoginEvent("LOGIN", "u-b1", now - 1000, null)));
        when(kc.bruteForce(any(), eq("u-a2"))).thenReturn(new BruteForceStatus(true, 5));
        UserRepresentation waiting = users.get("u-a3");
        waiting.setRequiredActions(List.of("UPDATE_PASSWORD"));

        mvc.perform(get("/institution-admin/overview").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value(A))
                .andExpect(jsonPath("$.members").value(3))
                .andExpect(jsonPath("$.leavers.available").value(false))
                .andExpect(jsonPath("$.leavers.error").value("The IPPIS employee mirror is not configured."))
                .andExpect(jsonPath("$.password_setup.available").value(true))
                .andExpect(jsonPath("$.password_setup.counts.total").value(1))
                .andExpect(jsonPath("$.password_setup.items[0].setup_email").value("unknown"))
                .andExpect(jsonPath("$.logins.available").value(true))
                .andExpect(jsonPath("$.logins.logins_24h").value(1))
                .andExpect(jsonPath("$.logins.failed_24h").value(1))
                .andExpect(jsonPath("$.logins.locked_now[0].username").value("andre"))
                .andExpect(jsonPath("$.access.available").value(true))
                .andExpect(jsonPath("$.access.items[*].client_id", containsInAnyOrder("minagri-kubaka-web", "gov-portal")))
                // groups ARE expanded on the institution side: minagri group = 2 direct members (+ andre named)
                .andExpect(jsonPath("$.access.items[?(@.client_id=='minagri-kubaka-web')].people").value(2))
                .andExpect(jsonPath("$.access.items[?(@.client_id=='gov-portal')].people").value(2));
    }

    @Test
    void overviewWithoutViewClientsSaysSoInsteadOfZero() throws Exception {
        mvc.perform(get("/institution-admin/overview").with(TestCallers.institutionAdmin(A, "hr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.available").value(false))
                .andExpect(jsonPath("$.access.error").value("Your role does not include view-clients."));
    }

    @Test
    void anUnreadableEventStoreOnlyTakesTheLoginSection() throws Exception {
        when(kc.loginEvents(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("events disabled"));
        mvc.perform(get("/institution-admin/overview").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logins.available").value(false))
                .andExpect(jsonPath("$.logins.error").value("events disabled"))
                .andExpect(jsonPath("$.access.available").value(true));
    }

    // ---- audit -----------------------------------------------------------------------------

    @Test
    void auditForcesTheInstitutionFilterAndDropsForeignEvents() throws Exception {
        when(auditClient.listEventsForInstitution(eq(A), any())).thenReturn(json.valueToTree(Map.of("total", 3, "limit", 100, "offset", 0, "items", List.of(
                Map.of("id", 1, "institution", A, "event_type", "admin.user.created"),
                Map.of("id", 2, "institution", B, "event_type", "admin.user.created"),
                Map.of("id", 3, "event_type", "auth.login.success")))));
        mvc.perform(get("/institution-admin/audit").param("event_type", "admin.user.created")
                        .with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.scope_filtered_out").value(2));
        verify(auditClient).listEventsForInstitution(eq(A),
                argThat(q -> "admin.user.created".equals(q.eventType()) && q.institution() == null));
    }

    @Test
    void auditLimitIsValidated() throws Exception {
        mvc.perform(get("/institution-admin/audit").param("limit", "5000").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void auditNeedsViewMembers() throws Exception {
        // every shipped tier has view-members; a platform admin acting for an institution passes too
        mvc.perform(get("/institution-admin/audit").param("institution", B).with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        verify(auditClient).listEventsForInstitution(eq(B), any());
    }
}
