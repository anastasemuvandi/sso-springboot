package rw.gov.risa.sso.wrapper.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * The platform-admin services against a real Keycloak 26.2 ({@link KeycloakIT}): client create → scope →
 * per-user access grant → the user's access state; departments from {@code /children}; realm role mapping;
 * the identifier write gate.
 */
@Tag("it")
class PlatformAdminIT {

    final ItServices it = new ItServices();
    final CallerPrincipal caller = TestCallers.caller("platform.super", Set.of("super_admin"), null);
    final PlatformSupport support = new PlatformSupport(KeycloakIT.executors(), it.directory, it.scopes, it.audit);
    final Mailer mailer = mock(Mailer.class);
    final ClientAdminService clients = new ClientAdminService(support, it.joinKeys, mock(MfaService.class),
            new PlatformProperties(false));
    final AuthorizationAdminService authz = new AuthorizationAdminService(support, it.access,
            new AccessChangeNotifier(mailer));
    final AgencyAdminService agencies = new AgencyAdminService(support);
    final RoleAdminService roles = new RoleAdminService(support);
    final UserAdminService users = new UserAdminService(support, it.joinKeys);

    PlatformAdminIT() {
        KeycloakIT.container();
        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", true));
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
    }

    @Test
    void createScopeGrantAndTheUsersAccessState() {
        String clientId = "minagri-" + KeycloakIT.unique("plat");
        ClientAdminService.ClientView created = clients.create(new ClientAdminService.NewClient(clientId,
                List.of("http://localhost:9999/*"), null, null, false, null, "minagri"), caller);
        assertThat(created.scope()).containsEntry("kind", "institution").containsEntry("inferred", false);
        assertThat(created.joinColumn()).containsEntry("explicit", false);

        // Widening to cross-cutting needs no confirmation.
        var change = clients.changeScope(clientId, true, List.of("minagri", "minisante"), null, false, caller);
        assertThat(change.scope()).containsEntry("kind", "cross-cutting");

        String userId = KeycloakIT.createUser(KeycloakIT.unique("plat.staff"), "minagri");
        ClientAccessViews.AccessItem before = item(userId, clientId);
        assertThat(before.state()).isEqualTo("not_granted");

        Map<String, Object> granted = authz.changeUserAccess(userId, clientId, "grant", "not_granted", caller);
        assertThat(granted).containsEntry("previous_state", "not_granted").containsEntry("notified", "sent");
        assertThat(item(userId, clientId).state()).isEqualTo("granted");

        // A stale expected_state is refused and changes nothing.
        assertThatThrownBy(() -> authz.changeUserAccess(userId, clientId, "grant", "not_granted", caller))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("state_changed"));

        authz.changeUserAccess(userId, clientId, "revoke", "granted", caller);
        assertThat(item(userId, clientId).state()).isEqualTo("not_granted");
        assertThat(authz.clientAccess(clientId).get("users")).asList().isEmpty();

        clients.delete(clientId, caller);
    }

    private ClientAccessViews.AccessItem item(String userId, String clientId) {
        return authz.userClientAccess(userId).items().stream().filter(i -> clientId.equals(i.clientId())).findFirst()
                .orElseThrow();
    }

    @Test
    void departmentsComeFromChildren() {
        String minagri = KeycloakIT.groupId("/minagri");
        assertThat(agencies.subgroups(minagri)).extracting(AgencyAdminService.SubgroupInfo::path)
                .contains("/minagri/planning");
        AgencyAdminService.AgencyView view = agencies.get(minagri);
        assertThat(view.subGroups()).extracting(AgencyAdminService.SubgroupInfo::name).contains("planning");
        assertThat(view.totalSubgroups()).isGreaterThanOrEqualTo(1);

        String name = KeycloakIT.unique("dept");
        AgencyAdminService.SubgroupInfo dept = agencies.createSubgroup(minagri, name, null, caller);
        assertThat(dept.path()).isEqualTo("/minagri/" + name);
        assertThat(agencies.subgroups(minagri)).extracting(AgencyAdminService.SubgroupInfo::id).contains(dept.id());
    }

    @Test
    void realmRoleMapping() {
        String userId = KeycloakIT.createUser(KeycloakIT.unique("plat.role"), "minagri");
        KeycloakIT.ensureRealmRole("inst:minagri:staff");
        roles.changeUserRealmRoles(userId, List.of("inst:minagri:staff"), true, caller);
        assertThat(roles.userRoles(userId).realmRoles()).extracting(RoleAdminService.RoleView::name)
                .contains("inst:minagri:staff");
        assertThat(users.get(userId).realmRoles()).contains("inst:minagri:staff");
        roles.changeUserRealmRoles(userId, List.of("inst:minagri:staff"), false, caller);
        assertThat(roles.userRoles(userId).realmRoles()).extracting(RoleAdminService.RoleView::name)
                .doesNotContain("inst:minagri:staff");
        assertThatThrownBy(() -> roles.changeUserRealmRoles(userId, List.of("no-such-role"), true, caller))
                .hasMessageContaining("Unknown realm role");
    }

    @Test
    void identifierWritesAreGated() {
        String userId = KeycloakIT.createUser(KeycloakIT.unique("plat.nid"), "minagri");
        assertThatThrownBy(() -> users.update(userId, new UserAdminService.UserChanges(null, null, null, null, null,
                Map.of("national_id", List.of("12"))), caller))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        String nid = "11998" + String.format("%011d", System.nanoTime() % 100_000_000_000L);
        users.update(userId, new UserAdminService.UserChanges(null, null, null, null, null,
                Map.of("national_id", List.of(nid))), caller);
        String other = KeycloakIT.createUser(KeycloakIT.unique("plat.nid2"), "minagri");
        assertThatThrownBy(() -> users.update(other, new UserAdminService.UserChanges(null, null, null, null, null,
                Map.of("national_id", List.of(nid))), caller))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // institution attribute preserved by the merge
        assertThat(users.get(userId).attributes()).containsEntry("institution", List.of("minagri"))
                .containsEntry("national_id", List.of(nid));
    }
}
