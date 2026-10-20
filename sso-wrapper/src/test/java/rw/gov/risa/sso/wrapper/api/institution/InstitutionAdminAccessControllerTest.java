package rw.gov.risa.sso.wrapper.api.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** Own-institution behaviour of {@code /institution-admin/access/*} (spec §5.13). */
class InstitutionAdminAccessControllerTest extends InstitutionAdminMvcSupport {

    private static MockHttpServletRequestBuilder revoke(String body) {
        return post("/institution-admin/access/revoke").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void clientsForUsersReportsBothWaysRound() throws Exception {
        mvc.perform(post("/institution-admin/access/clients-for-users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_ids\":[\"u-a1\",\"u-a3\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value(A))
                .andExpect(jsonPath("$.clients[*].client_id", containsInAnyOrder("minagri-kubaka-web", "gov-portal")))
                .andExpect(jsonPath("$.by_user['u-a1']", containsInAnyOrder("minagri-kubaka-web", "gov-portal")))
                // a department member is NOT admitted by the parent group's grant (direct members only)
                .andExpect(jsonPath("$.by_user['u-a3']").isEmpty())
                .andExpect(jsonPath("$.usernames['u-a1']").value("alice"))
                .andExpect(jsonPath("$.total_pairs").value(2));
    }

    @Test
    void anEmptySelectionIs422() throws Exception {
        mvc.perform(post("/institution-admin/access/clients-for-users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_ids\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aGroupGrantIsRevokedByADenyEntryAndTheGroupIsNeverTouched() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-kubaka-web\"]}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(false))
                .andExpect(jsonPath("$.users[0].revoked[0]").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$.total_revocations").value(1))
                .andExpect(jsonPath("$.users[0].notified").value("not_configured"))
                .andExpect(jsonPath("$.notifications.not_configured").value(1));
        assertThat(denied("minagri-kubaka-web")).containsExactly("u-a1");
        assertThat(assignedGroups("minagri-kubaka-web")).containsExactly("g-minagri");
        assertThat(memberships.get("u-a1")).containsExactly("g-minagri");
        verify(audit).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED
                && "revoke_user_access".equals(e.extra().get("action")) && "u-a1".equals(e.extra().get("target_user"))
                && "minagri-kubaka-web".equals(e.extra().get("target_client"))
                && Boolean.TRUE.equals(e.extra().get("denied")) && "minagri.admin".equals(e.actor())));
    }

    @Test
    void holdingBothWaysIsRevokedBothWaysInOneWritePerClient() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\",\"u-a2\"],\"client_ids\":[\"minagri-kubaka-web\"]}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_revocations").value(2))
                .andExpect(jsonPath("$.users_affected").value(2));
        assertThat(named("minagri-kubaka-web")).isEmpty();
        assertThat(denied("minagri-kubaka-web")).containsExactlyInAnyOrder("u-a1", "u-a2");
        verify(policyStore, times(1)).apply(any(), anyString(), anyList());
    }

    @Test
    void aUserWhoDoesNotHoldTheClientIsSkippedAndRevokingTwiceIsAlreadyRevoked() throws Exception {
        String body = "{\"user_ids\":[\"u-a1\",\"u-a3\"],\"client_ids\":[\"minagri-kubaka-web\"]}";
        mvc.perform(revoke(body).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[1].skipped[0].reason").value("not_assigned"));
        mvc.perform(revoke(body).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].skipped[0].reason").value("already_revoked"))
                .andExpect(jsonPath("$.total_revocations").value(0));
    }

    @Test
    void aPreviewChangesNothingAndIsNotAuditedOrEmailed() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-kubaka-web\"],\"preview\":true}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.users[0].revoked[0]").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$.users[0].notified").doesNotExist())
                .andExpect(jsonPath("$.notifications").isEmpty());
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-kubaka-web\"],\"dry_run\":true}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true));
        assertThat(denied("minagri-kubaka-web")).isEmpty();
        verify(policyStore, never()).apply(any(), anyString(), anyList());
        verify(audit, never()).emit(auditEvent(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED));
    }

    @Test
    void allClientsWithoutConfirmationIsAPreviewAndWithItStripsEverything() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"all_clients\":true}").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.clients_considered", containsInAnyOrder("gov-portal", "minagri-kubaka-web")));
        assertThat(denied("gov-portal")).isEmpty();
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"all_clients\":true,\"confirm_all_clients\":true}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value(false))
                .andExpect(jsonPath("$.total_revocations").value(2));
        assertThat(denied("gov-portal")).containsExactly("u-a1");
        assertThat(denied("minagri-kubaka-web")).containsExactly("u-a1");
    }

    @Test
    void allClientsAndAListTogetherOrNothingAtAllIsRefused() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"all_clients\":true,\"client_ids\":[\"gov-portal\"]}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isBadRequest());
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"]}").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSharedClientRevokesOnlyMyUsersWithTheServiceAccount() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"gov-portal\"]}")
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_revocations").value(1));
        assertThat(denied("gov-portal")).containsExactly("u-a1");
        assertThat(assignedGroups("gov-portal")).containsExactly("g-minagri", "g-minisante");
        verify(policyStore).apply(any(), org.mockito.ArgumentMatchers.eq(uuid("gov-portal")), anyList());
    }

    @Test
    void aTierWithoutManageClientsCannotRevoke() throws Exception {
        mvc.perform(revoke("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-kubaka-web\"]}")
                        .with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
        verify(policyStore, never()).apply(any(), anyString(), anyList());
    }
}
