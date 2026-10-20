package rw.gov.risa.sso.wrapper.api.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** Own-institution behaviour of {@code /institution-admin/apps/*}. */
class InstitutionAdminAppsControllerTest extends InstitutionAdminMvcSupport {

    @Test
    void listShowsOwnAndSharedAppsButNeverTheControlPlane() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].clientId", containsInAnyOrder("minagri-kubaka-web", "gov-portal")))
                .andExpect(jsonPath("$[?(@.clientId=='gov-portal')].owned").value(false))
                .andExpect(jsonPath("$[?(@.clientId=='gov-portal')].cross_cutting").value(true))
                .andExpect(jsonPath("$[?(@.clientId=='minagri-kubaka-web')].manageable").value(true))
                .andExpect(jsonPath("$[?(@.clientId=='minagri-kubaka-web')].mfa_required").value(true));
    }

    @Test
    void aReadOnlyTierSeesAppsButNothingIsManageable() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].manageable", not(hasItem(true))));
    }

    @Test
    void anHrTierHasNoClientScope() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "hr")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
    }

    // ---- create ----------------------------------------------------------------------------

    @Test
    void createComposesSlugDashAppAndRecordsTheOwner() throws Exception {
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"portal\",\"redirect_uris\":[\"https://a/cb\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("minagri-portal"))
                .andExpect(jsonPath("$.scope.owner_institution").value(A))
                .andExpect(jsonPath("$.scope.cross_cutting").value(false))
                .andExpect(jsonPath("$.secret").doesNotExist());
        verify(kc).createClient(argThat(e -> !e.asCaller()), argThat((ClientRepresentation c) ->
                "minagri-portal".equals(c.getClientId())
                        && A.equals(c.getAttributes().get("sso.client.owner_institution"))
                        && Boolean.TRUE.equals(c.getAuthorizationServicesEnabled())));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_CLIENT_CREATED
                && "minagri-portal".equals(e.extra().get("target_client")) && A.equals(e.extra().get("institution"))));
    }

    @Test
    void anAppNameSpellingAnotherInstitutionStillLandsInMine() throws Exception {
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"minisante-hmis2\",\"redirect_uris\":[\"https://a/cb\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("minagri-minisante-hmis2"))
                .andExpect(jsonPath("$.scope.owner_institution").value(A));
    }

    @Test
    void aPastedClientIdIsRefusedRatherThanDoubledUp() throws Exception {
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"minagri-portal\",\"redirect_uris\":[\"https://a/cb\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("app part only")));
        verify(kc, never()).createClient(any(), any());
    }

    @Test
    void anyInstitutionArgumentInTheBodyIsRefused() throws Exception {
        for (String extra : new String[] {"\"institution\":\"minisante\"", "\"owner_institution\":\"minisante\"",
                "\"cross_cutting\":true", "\"client_id\":\"minisante-x\""}) {
            mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"app\":\"portal\",\"redirect_uris\":[\"https://a/cb\"]," + extra + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        verify(kc, never()).createClient(any(), any());
    }

    @Test
    void createNeedsManageClientsAndRedirectUris() throws Exception {
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"portal\",\"redirect_uris\":[\"https://a/cb\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"app\":\"portal\",\"redirect_uris\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- secret ----------------------------------------------------------------------------

    @Test
    void ownSecretIsReadableWithTheServiceAccountSharedIsNot() throws Exception {
        mvc.perform(get("/institution-admin/apps/minagri-kubaka-web/secret").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_secret").value("s3cret"));
        verify(kc).clientSecret(argThat(e -> !e.asCaller()), eq("minagri-kubaka-web"));
        mvc.perform(get("/institution-admin/apps/gov-portal/secret").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("cross_cutting_client"));
    }

    @Test
    void anUnknownClientIs404() throws Exception {
        mvc.perform(get("/institution-admin/apps/minagri-ghost/secret").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isNotFound());
    }

    // ---- join column -----------------------------------------------------------------------

    @Test
    void joinColumnIsReadableForOwnAndSharedApps() throws Exception {
        when(joinKeys.getSetting(anyString())).thenAnswer(i -> JoinColumnSetting.unset(i.getArgument(0)));
        when(joinKeys.activeSessions(anyString())).thenReturn(0);
        mvc.perform(get("/institution-admin/apps/minagri-kubaka-web/join-column").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting.join_column").value("email"))
                .andExpect(jsonPath("$.active_sessions").value(0))
                .andExpect(jsonPath("$.readiness").doesNotExist());
        mvc.perform(get("/institution-admin/apps/gov-portal/join-column").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk());
    }

    @Test
    void settingTheJoinColumnOnOwnAppIsAuditedAndSharedIsRefused() throws Exception {
        JoinColumnSetting before = JoinColumnSetting.unset("minagri-kubaka-web");
        JoinColumnSetting after = new JoinColumnSetting("minagri-kubaka-web", JoinColumn.NID, true);
        when(joinKeys.setJoinColumn(eq("minagri-kubaka-web"), eq(JoinColumn.NID), eq("minagri.admin"), anyBoolean()))
                .thenReturn(new JoinKeyService.JoinColumnChange(after, before, 0, 0, "created",
                        new JoinKeyService.Readiness(after, 0, false, java.util.List.of())));
        mvc.perform(put("/institution-admin/apps/minagri-kubaka-web/join-column").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"nid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapper").value("created"));
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED
                && "join_column_changed".equals(e.extra().get("action"))));

        mvc.perform(put("/institution-admin/apps/gov-portal/join-column").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"nid\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("cross_cutting_client"));
        mvc.perform(put("/institution-admin/apps/minagri-kubaka-web/join-column").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"passport\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void joinColumnsWorklistIsTheInstitutions() throws Exception {
        when(joinKeys.backfillWorklist(any())).thenAnswer(i -> Map.of("institution",
                ((rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext) i.getArgument(0)).institution()));
        mvc.perform(get("/institution-admin/apps/join-columns").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value(A));
    }

    // ---- MFA -------------------------------------------------------------------------------

    @Test
    void mfaCanBeTurnedOnForOwnApp() throws Exception {
        when(mfa.setClientRequirement(eq("minagri-kubaka-web"), eq(true), eq("minagri.admin"), any(), eq(A)))
                .thenReturn(new MfaService.ClientRequirementChange("minagri-kubaka-web", true, false, true,
                        "per_client", true));
        mvc.perform(put("/institution-admin/apps/minagri-kubaka-web/mfa").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void mfaOffIsRefused() throws Exception {
        mvc.perform(put("/institution-admin/apps/minagri-kubaka-web/mfa").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\":false}"))
                .andExpect(status().isForbidden());
        verify(mfa, never()).setClientRequirement(anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void mfaOnACrossCuttingAppIsRefused() throws Exception {
        mvc.perform(put("/institution-admin/apps/gov-portal/mfa").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("cross_cutting_client"))
                .andExpect(jsonPath("$.message", containsString("platform admin")));
        verify(mfa, never()).setClientRequirement(anyString(), anyBoolean(), any(), any(), any());
    }

    // ---- institution-wide access -----------------------------------------------------------

    @Test
    void grantAddsMyGroupAsTheCallerOnAnOwnApp() throws Exception {
        policies.remove(uuid("minagri-kubaka-web"));
        mvc.perform(post("/institution-admin/apps/minagri-kubaka-web/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].id").value("g-minagri"));
        assertThat(assignedGroups("minagri-kubaka-web")).containsExactly("g-minagri");
        verify(policyStore).apply(argThat(r -> r == REALM), eq(uuid("minagri-kubaka-web")), any());
    }

    @Test
    void revokeOnASharedAppRemovesOnlyMyInstitution() throws Exception {
        mvc.perform(delete("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed_groups[0].id").value("g-minagri"));
        assertThat(assignedGroups("gov-portal")).containsExactly("g-minisante");
    }

    // ---- who holds an app ------------------------------------------------------------------

    @Test
    void appUsersListsMyHoldersDirectAndViaGroup() throws Exception {
        mvc.perform(get("/institution-admin/apps/minagri-kubaka-web/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.revocable").value(true))
                .andExpect(jsonPath("$.items[?(@.id=='u-a2')].direct").value(true))
                .andExpect(jsonPath("$.items[?(@.id=='u-a1')].via_groups[0]").value("minagri"));
    }

    @Test
    void aSharedAppShowsOnlyMyPeopleAndReadsTheForeignGroupWithTheServiceAccount() throws Exception {
        mvc.perform(get("/institution-admin/apps/gov-portal/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cross_cutting").value(true))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder("u-a1", "u-a2")));
        // the cross-cutting client's policies are the platform's: service account
        verify(policyStore, org.mockito.Mockito.atLeastOnce()).read(any(), eq(uuid("gov-portal")));
        verify(kc).groupMembers(argThat(e -> !e.asCaller()), eq("g-minisante"));
        verify(kc, org.mockito.Mockito.atLeastOnce()).groupMembers(argThat(KeycloakExec::asCaller), eq("g-minagri"));
    }

    @Test
    void anAlreadyRevokedUserIsNotListed() throws Exception {
        policies.get(uuid("minagri-kubaka-web")).add(rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy.user(
                "p9", rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.DENIED_USERS, true,
                java.util.List.of("u-a1")));
        mvc.perform(get("/institution-admin/apps/minagri-kubaka-web/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder("u-a2")));
    }
}
