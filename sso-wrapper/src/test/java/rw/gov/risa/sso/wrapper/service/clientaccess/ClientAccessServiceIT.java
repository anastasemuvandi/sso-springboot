package rw.gov.risa.sso.wrapper.service.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessState;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

@Tag("it")
class ClientAccessServiceIT {

    static ItServices s;
    static KeycloakExec sa;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        s = new ItServices();
        sa = s.access.serviceAccount();
    }

    @Test
    void noPolicyMeansDefaultDenyThenAdmitCreatesThePoliciesByType() {
        String client = KeycloakIT.unique("minagri-app");
        KeycloakIT.createClient(client);
        String user = KeycloakIT.unique("ca-user");
        String uid = KeycloakIT.createUser(user, "minagri");

        assertThat(s.access.checkLogin(user, client).hasAccess()).isFalse();
        assertThat(s.access.checkLogin(user, client).reason()).isEqualTo(AccessReason.NO_POLICY);

        s.access.admitUser(sa, client, uid);
        List<AccessPolicy> policies = s.access.policies(sa, client);
        assertThat(policies).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo("sso-access-users");
            assertThat(p.type()).isEqualTo("user");
        });
        assertThat(s.access.checkLogin(user, client).hasAccess()).isTrue();
        // Idempotent
        assertThat(s.access.admitUser(sa, client, uid).changed()).isFalse();
        assertThat(s.access.describeAssignment(sa, client).users()).extracting("name").contains(user);
    }

    @Test
    void denyBeatsAllowAndAdmitLiftsTheDenial() {
        String client = KeycloakIT.unique("minagri-deny");
        KeycloakIT.createClient(client);
        String user = KeycloakIT.unique("ca-deny");
        String uid = KeycloakIT.createUser(user, "minagri");
        String group = KeycloakIT.groupId("/minagri");

        s.access.grantGroup(sa, client, group);
        assertThat(s.access.checkLogin(user, client).hasAccess()).isTrue();

        var revoke = s.access.revokeUserIds(sa, client, List.of(uid));
        assertThat(revoke.denied()).containsExactly(uid);
        assertThat(s.access.checkLogin(user, client).reason()).isEqualTo(AccessReason.DENIED);
        assertThat(s.access.decideForUserIds(sa, client, List.of(uid)).get(uid).state()).isEqualTo(AccessState.REVOKED);
        // A group-member who was not revoked still gets in
        assertThat(s.access.checkLogin("minagri.staff", client).hasAccess()).isTrue();

        var admitted = s.access.admitUser(sa, client, uid);
        assertThat(admitted.liftedDenial()).isTrue();
        assertThat(s.access.checkLogin(user, client).hasAccess()).isTrue();
    }

    @Test
    void revokeNamedGrantAndRestore() {
        String client = KeycloakIT.unique("minisante-rev");
        KeycloakIT.createClient(client);
        String a = KeycloakIT.createUser(KeycloakIT.unique("ca-a"), "minisante");
        String b = KeycloakIT.createUser(KeycloakIT.unique("ca-b"), "minisante");
        s.access.admitUsers(sa, client, List.of(a, b));

        var r = s.access.revokeUserIds(sa, client, List.of(a));
        assertThat(r.unassigned()).containsExactly(a);
        assertThat(s.access.assignment(sa, client).userIds()).containsExactly(b);

        s.access.denyUsers(sa, client, List.of(b));
        assertThat(s.access.deniedUserIds(sa, client)).containsExactly(b);
        assertThat(s.access.restore(sa, client, List.of(b)).changedUserIds()).containsExactly(b);
        assertThat(s.access.deniedUserIds(sa, client)).isEmpty();
    }

    @Test
    void userViewAcrossClientsAndInstitutionWithdrawal() {
        String client = KeycloakIT.unique("shared-app");
        KeycloakIT.createClient(client);
        String mine = KeycloakIT.createUser(KeycloakIT.unique("ca-mine"), "minagri");
        String theirs = KeycloakIT.createUser(KeycloakIT.unique("ca-theirs"), "minisante");
        s.access.admitUsers(sa, client, List.of(mine, theirs));
        s.access.grantGroup(sa, client, KeycloakIT.groupId("/minagri"));

        var view = s.access.userClientAccess(sa, mine);
        assertThat(view.items()).anySatisfy(i -> {
            assertThat(i.clientId()).isEqualTo(client);
            assertThat(i.state()).isEqualTo("granted");
        });
        assertThat(view.items()).anySatisfy(i -> {
            assertThat(i.clientId()).isEqualTo("sso-admin-ui");
            assertThat(i.state()).isEqualTo("exempt");
        });

        Map<String, ?> summary = s.access.institutionAccessSummary(sa, client, List.of("minagri", "minisante"));
        assertThat(summary.get("minagri").toString()).contains("groupAssigned=true", "users=1");

        var w = s.access.removeInstitutionAccess(sa, client, "minagri", null);
        assertThat(w.removedUserIds()).containsExactly(mine);
        assertThat(w.removedGroups()).hasSize(1);
        assertThat(s.access.assignment(sa, client).userIds()).containsExactly(theirs);
        assertThat(s.access.assignment(sa, client).groupIds()).isEmpty();
    }

    @Test
    void removeNamedGrantsEverywhereForALeaver() {
        String c1 = KeycloakIT.unique("leaver-a");
        String c2 = KeycloakIT.unique("leaver-b");
        KeycloakIT.createClient(c1);
        KeycloakIT.createClient(c2);
        String uid = KeycloakIT.createUser(KeycloakIT.unique("ca-leaver"), "minagri");
        s.access.admitUser(sa, c1, uid);
        s.access.admitUser(sa, c2, uid);

        var removal = s.access.removeNamedGrantsEverywhere(uid);
        assertThat(removal.changedClientIds()).contains(c1, c2);
        assertThat(s.access.assignment(sa, c1).userIds()).doesNotContain(uid);
    }

    @Test
    void asCallerExecutionUsesTheCallersToken() {
        // A plain staff member holds no admin role: Keycloak itself refuses the policy read.
        String client = KeycloakIT.unique("caller-app");
        KeycloakIT.createClient(client);
        s.access.admitUser(sa, client, KeycloakIT.userId("minagri.staff"));
        KeycloakExec asStaff = KeycloakIT.executors().asCaller(KeycloakIT.token("minagri.staff"));
        assertThatThrownBy(() -> s.access.policies(asStaff, client))
                .isInstanceOf(jakarta.ws.rs.ForbiddenException.class);
        // ...and execFor(cross-cutting) falls back to the service account.
        assertThat(s.access.execFor(asStaff, true).asCaller()).isFalse();
    }

    @Test
    void unknownClientIs404() {
        assertThatThrownBy(() -> s.access.assignment(sa, "no-such-client"))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");
    }
}
