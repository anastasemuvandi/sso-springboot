package rw.gov.risa.sso.wrapper.api.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * {@code /dashboard/*}: the overview's four independent sections (available/error, never a false zero), its
 * 60 s cache and {@code ?refresh=true}, the gate, and the live metrics.
 */
@WrapperTest
class DashboardControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;
    @MockitoBean AccessPolicyStore policies;
    @MockitoBean MailDeliveryLog deliveries;
    @MockitoBean StringRedisTemplate redis;

    RealmResource realm;

    static UserRepresentation user(String id, String username, String... requiredActions) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@gov.rw");
        u.setEnabled(true);
        u.setRequiredActions(List.of(requiredActions));
        return u;
    }

    static ClientRepresentation client(String uuid, String clientId) {
        ClientRepresentation c = new ClientRepresentation();
        c.setId(uuid);
        c.setClientId(clientId);
        c.setName(clientId);
        c.setAttributes(Map.of());
        return c;
    }

    static EventRepresentation event(String type, String userId, long time, String error) {
        EventRepresentation e = new EventRepresentation();
        e.setType(type);
        e.setUserId(userId);
        e.setTime(time);
        e.setError(error);
        e.setClientId("minagri-kubaka-web");
        return e;
    }

    @BeforeEach
    void realm() {
        realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
        when(realm.users().search(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt(), isNull(), anyBoolean()))
                .thenReturn(List.of(user("u1", "alice", "UPDATE_PASSWORD"), user("u2", "bob", "UPDATE_PASSWORD"),
                        user("u3", "carol")));
        when(realm.clients().findAll()).thenReturn(List.of(client("c1", "minagri-kubaka-web"),
                client("c0", "sso-client"), client("c9", "account")));
        when(policies.read(any(), eq("c1"))).thenReturn(List.of(
                new AccessPolicy("p1", "sso-access-users", "user", "POSITIVE", Map.of("users", "[\"u1\",\"u3\"]")),
                new AccessPolicy("p2", "sso-access-denied-users", "user", "NEGATIVE", Map.of("users", "[\"u2\"]"))));
        when(realm.getClientSessionStats()).thenReturn(List.of(Map.of("clientId", "minagri-kubaka-web", "active", "3")));
        long now = System.currentTimeMillis();
        List<EventRepresentation> events = new ArrayList<>(List.of(
                event("LOGIN", "u1", now - 1000, null),
                event("LOGIN_ERROR", "u2", now - 2000, "invalid_user_credentials"),
                event("LOGIN", "u3", now - 3 * 86_400_000L, null)));
        when(realm.getEvents(any(), isNull(), isNull(), anyLong(), anyLong(), isNull(), anyInt(), anyInt(), anyString()))
                .thenReturn(events);
        when(realm.attackDetection().bruteForceUserStatus("u2")).thenReturn(Map.of("disabled", true, "numFailures", 5));
        when(deliveries.last("u1")).thenReturn(java.util.Optional.of(new Delivery("failed", "provisioning", "t")));
        when(deliveries.last("u2")).thenReturn(java.util.Optional.empty());
    }

    @Test
    void institutionAdminIsRefusedAndSuperAdminPasses() throws Exception {
        mvc.perform(get("/dashboard/overview").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/dashboard/metrics").with(TestCallers.plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void everySectionCarriesAvailableAndErrorAndRealNumbers() throws Exception {
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").doesNotExist())
                .andExpect(jsonPath("$.members").value(3))
                .andExpect(jsonPath("$.generated_at").isString())
                // no mirror configured: unavailable WITH the reason, not "0 leavers"
                .andExpect(jsonPath("$.leavers.available").value(false))
                .andExpect(jsonPath("$.leavers.error").value("The IPPIS employee mirror is not configured."))
                .andExpect(jsonPath("$.password_setup.available").value(true))
                .andExpect(jsonPath("$.password_setup.counts.total").value(2))
                .andExpect(jsonPath("$.password_setup.counts.failed").value(1))
                .andExpect(jsonPath("$.password_setup.counts.unknown").value(1))
                .andExpect(jsonPath("$.password_setup.items[0].setup_email").value("failed"))
                .andExpect(jsonPath("$.logins.available").value(true))
                .andExpect(jsonPath("$.logins.logins_24h").value(1))
                .andExpect(jsonPath("$.logins.logins_7d").value(2))
                .andExpect(jsonPath("$.logins.failed_24h").value(1))
                .andExpect(jsonPath("$.logins.locked_now[0].sub").value("u2"))
                .andExpect(jsonPath("$.logins.locked_now[0].username").value("bob"))
                .andExpect(jsonPath("$.logins.truncated").value(false))
                .andExpect(jsonPath("$.logins.active_sessions").value(3))
                .andExpect(jsonPath("$.access.available").value(true))
                .andExpect(jsonPath("$.access.items.length()").value(1))
                .andExpect(jsonPath("$.access.items[0].client_id").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$.access.items[0].named").value(2))
                .andExpect(jsonPath("$.access.items[0].revoked").value(1))
                .andExpect(jsonPath("$.access.items[0].people").doesNotExist())
                .andExpect(jsonPath("$.access.items[0].active_sessions").value(3));
    }

    @Test
    void aFailedReadMakesOnlyItsOwnSectionUnavailable() throws Exception {
        when(realm.users().search(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt(), isNull(), anyBoolean()))
                .thenThrow(new jakarta.ws.rs.ServiceUnavailableException("keycloak down"));
        when(realm.getEvents(any(), isNull(), isNull(), anyLong(), anyLong(), isNull(), anyInt(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("events store unavailable"));
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(jsonPath("$.password_setup.available").value(false))
                .andExpect(jsonPath("$.password_setup.error").isString())
                .andExpect(jsonPath("$.password_setup.counts").doesNotExist())
                .andExpect(jsonPath("$.logins.available").value(false))
                .andExpect(jsonPath("$.logins.error").value("events store unavailable"))
                .andExpect(jsonPath("$.logins.active_sessions").value(3))
                .andExpect(jsonPath("$.access.available").value(true));
    }

    @Test
    void anUnreadableClientIsNamedWhileTheSectionStaysAvailable() throws Exception {
        when(realm.clients().findAll()).thenReturn(List.of(client("c1", "minagri-kubaka-web"), client("c2", "rra-tax")));
        when(policies.read(any(), eq("c2"))).thenThrow(new jakarta.ws.rs.ForbiddenException("no"));
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(jsonPath("$.access.available").value(true))
                .andExpect(jsonPath("$.access.error").value("Could not read access for: rra-tax"));
    }

    @Test
    void theOverviewIsCachedAndRefreshBypassesIt() throws Exception {
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        mvc.perform(get("/dashboard/overview").with(TestCallers.superAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.members").value(3));
        mvc.perform(get("/dashboard/overview").with(TestCallers.superAdmin())).andExpect(status().isOk());
        verify(realm, times(1)).getClientSessionStats(); // two cache hits, one build
        mvc.perform(get("/dashboard/overview").param("refresh", "true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        verify(realm, times(2)).getClientSessionStats();
    }

    // ---- live metrics ----------------------------------------------------------------------

    @Test
    void countsSessionsAndActivity() throws Exception {
        when(realm.users().count()).thenReturn(42);
        mvc.perform(get("/dashboard/users/count").with(TestCallers.admin()))
                .andExpect(jsonPath("$.total_users").value(42));
        mvc.perform(get("/dashboard/clients/count").with(TestCallers.admin()))
                .andExpect(jsonPath("$.total_clients").value(3));
        mvc.perform(get("/dashboard/sessions").with(TestCallers.admin()))
                .andExpect(jsonPath("$.total_sessions").value(3))
                .andExpect(jsonPath("$.client_sessions[0].clientId").value("minagri-kubaka-web"));
        // (the stub answers every window with the same three events: Keycloak filters by date, not this code)
        mvc.perform(get("/dashboard/activity/24h").with(TestCallers.admin()))
                .andExpect(jsonPath("$.logins").value(2))
                .andExpect(jsonPath("$.failed_logins").value(1))
                .andExpect(jsonPath("$.total_events").value(3));
        mvc.perform(get("/dashboard/logins/recent").with(TestCallers.admin()))
                .andExpect(jsonPath("$[1].status").value("FAILED"));
        mvc.perform(get("/dashboard/logins/stats").param("period", "24h").with(TestCallers.admin()))
                .andExpect(jsonPath("$.period").value("24h"))
                .andExpect(jsonPath("$.data.length()").value(24))
                .andExpect(jsonPath("$.total_logins").value(3));
        mvc.perform(get("/dashboard/logins/recent").param("max_items", "500").with(TestCallers.admin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void metricsAreRealNotMocked() throws Exception {
        when(realm.users().count()).thenReturn(3);
        org.keycloak.representations.idm.GroupRepresentation g = new org.keycloak.representations.idm.GroupRepresentation();
        g.setName("minagri");
        org.keycloak.representations.idm.GroupRepresentation other = new org.keycloak.representations.idm.GroupRepresentation();
        other.setName("administrators");
        when(realm.groups().groups(0, 10_000)).thenReturn(List.of(g, other));
        mvc.perform(get("/dashboard/metrics").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_users").value(3))
                .andExpect(jsonPath("$.total_agencies").value(1))
                .andExpect(jsonPath("$.active_sessions").value(3))
                .andExpect(jsonPath("$.logins_today").value(3))
                .andExpect(jsonPath("$.new_users_this_week").value(0));
    }
}
