package rw.gov.risa.sso.wrapper.policy.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.NamedGroup;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;

class ClientAccessWritesTest {

    static ClientAssignment of(List<String> users, List<String> groups, List<String> denied) {
        return new ClientAssignment(users, groups, denied);
    }

    @Test
    void admitUserAddsTheNameAndLiftsTheirDenial() {
        ClientAssignment before = of(List.of("bob"), List.of("gid-a"), List.of("alice", "carol"));
        ClientAccessWrites.AdmitResult r = ClientAccessWrites.admitUser(before, "alice");
        assertThat(r.admitted()).isTrue();
        assertThat(r.liftedDenial()).isTrue();
        assertThat(r.assignment().userIds()).containsExactly("bob", "alice");
        assertThat(r.assignment().deniedUserIds()).containsExactly("carol"); // only the person named
        assertThat(r.assignment().groupIds()).containsExactly("gid-a");
        // And the decider agrees the person is now in.
        AccessDecision d = ClientAccessDecider.decide("alice", List.of(), ClientPolicies.parse(List.of(
                AccessPolicy.user("1", CanonicalPolicies.USERS, false, r.assignment().userIds()),
                AccessPolicy.user("2", CanonicalPolicies.DENIED_USERS, true, r.assignment().deniedUserIds()))), false, true);
        assertThat(d.state()).isEqualTo(AccessState.GRANTED);
    }

    @Test
    void admitUserIsIdempotent() {
        ClientAssignment before = of(List.of("alice"), List.of(), List.of());
        ClientAccessWrites.AdmitResult r = ClientAccessWrites.admitUser(before, "alice");
        assertThat(r.changed()).isFalse();
        assertThat(r.assignment()).isEqualTo(before);
    }

    @Test
    void admitUserAlreadyNamedButDeniedStillLifts() {
        ClientAccessWrites.AdmitResult r = ClientAccessWrites.admitUser(of(List.of("alice"), List.of(), List.of("alice")), "alice");
        assertThat(r.admitted()).isFalse();
        assertThat(r.liftedDenial()).isTrue();
        assertThat(r.assignment().deniedUserIds()).isEmpty();
    }

    @Test
    void admitUsersInOneWrite() {
        ClientAccessWrites.AdmitManyResult r = ClientAccessWrites.admitUsers(
                of(List.of("bob"), List.of("g"), List.of("carol", "dave")), List.of("bob", "carol", "", "erin", "carol"));
        assertThat(r.admitted()).containsExactly("carol", "erin");
        assertThat(r.already()).containsExactly("bob");
        assertThat(r.lifted()).containsExactly("carol");
        assertThat(r.assignment().userIds()).containsExactly("bob", "carol", "erin");
        assertThat(r.assignment().deniedUserIds()).containsExactly("dave");
    }

    @Test
    void admitInstitutionIsAdditiveNeverAReplace() {
        ClientAssignment before = of(List.of("x"), List.of("gid-b"), List.of());
        ClientAssignment after = ClientAccessWrites.admitInstitution(before, "gid-a");
        assertThat(after.groupIds()).containsExactly("gid-b", "gid-a");
        assertThat(after.userIds()).containsExactly("x");
        assertThat(ClientAccessWrites.admitInstitution(after, "gid-a")).isSameAs(after);
    }

    @Test
    void setAssignmentsLiftsOnlyThoseNamedByTheCall() {
        ClientAssignment after = ClientAccessWrites.setAssignments(
                of(List.of("alice", "bob"), List.of(), List.of("alice", "carol")), List.of("alice"), List.of("g"));
        assertThat(after.userIds()).containsExactly("alice");
        assertThat(after.groupIds()).containsExactly("g");
        assertThat(after.deniedUserIds()).containsExactly("carol");
    }

    @Test
    void denyAndAllowAgain() {
        ClientAccessWrites.DenyResult d = ClientAccessWrites.denyUsers(of(List.of(), List.of(), List.of("a")), List.of("a", "b", "b"));
        assertThat(d.changedUserIds()).containsExactly("b");
        assertThat(d.assignment().deniedUserIds()).containsExactly("a", "b");
        ClientAccessWrites.DenyResult l = ClientAccessWrites.allowUsersAgain(d.assignment(), List.of("b", "zz"));
        assertThat(l.changedUserIds()).containsExactly("b");
        assertThat(l.assignment().deniedUserIds()).containsExactly("a");
        assertThat(ClientAccessWrites.allowUsersAgain(l.assignment(), List.of("zz")).changedUserIds()).isEmpty();
    }

    @Test
    void revokeUsersAnswersBothGrantMechanisms() {
        ClientAssignment before = of(List.of("named", "both"), List.of("gid-a"), List.of("old"));
        ClientAccessWrites.RevokeResult r = ClientAccessWrites.revokeUsers(before, List.of(
                new RevokeTarget("named", List.of()),
                new RevokeTarget("viagroup", List.of("gid-a")),
                new RevokeTarget("both", List.of("gid-a")),
                new RevokeTarget("old", List.of("gid-a")),
                new RevokeTarget("nobody", List.of("gid-z")),
                new RevokeTarget("", List.of())));
        assertThat(r.unassigned()).containsExactly("named", "both");
        assertThat(r.denied()).containsExactly("viagroup", "both");
        assertThat(r.alreadyDenied()).containsExactly("old");
        assertThat(r.notAssigned()).containsExactly("nobody");
        assertThat(r.assignment().userIds()).isEmpty();
        assertThat(r.assignment().groupIds()).containsExactly("gid-a"); // the group stays
        assertThat(r.assignment().deniedUserIds()).containsExactly("old", "viagroup", "both");
    }

    @Test
    void withdrawInstitutionTakesItsGroupAndItsNamedUsersOnly() {
        ClientAssignment before = of(List.of("u-minagri", "u-minisante", "u-unknown", "u-dangling"),
                List.of("g1", "g2"), List.of("d"));
        ClientAccessWrites.WithdrawResult r = ClientAccessWrites.withdrawInstitution(before,
                List.of(new NamedGroup("g1", "MINAGRI"), new NamedGroup("g2", "minisante")),
                Map.of("u-minagri", List.of(" MinAgri "), "u-minisante", List.of("minisante"), "u-unknown", List.of()),
                "minagri", null);
        assertThat(r.changed()).isTrue();
        assertThat(r.removedGroups()).containsExactly(new NamedGroup("g1", "MINAGRI"));
        assertThat(r.removedUserIds()).containsExactly("u-minagri");
        assertThat(r.assignment().userIds()).containsExactly("u-minisante", "u-unknown", "u-dangling");
        assertThat(r.assignment().groupIds()).containsExactly("g2");
        assertThat(r.assignment().deniedUserIds()).containsExactly("d");
    }

    @Test
    void withdrawByGroupIdAndNothingToWithdraw() {
        ClientAssignment before = of(List.of(), List.of("g1"), List.of());
        assertThat(ClientAccessWrites.withdrawInstitution(before, List.of(new NamedGroup("g1", null)), Map.of(), "x", "g1")
                .removedGroups()).hasSize(1);
        ClientAccessWrites.WithdrawResult none = ClientAccessWrites.withdrawInstitution(before,
                List.of(new NamedGroup("g1", "other")), Map.of(), "minagri", null);
        assertThat(none.changed()).isFalse();
        assertThat(none.assignment()).isSameAs(before);
    }

    @Test
    void institutionAccessSummaryCountsBothMechanismsAndZeroes() {
        Map<String, ClientAccessWrites.InstitutionAccess> s = ClientAccessWrites.institutionAccessSummary(
                List.of(new NamedGroup("g", "MINAGRI")),
                Map.of("u1", List.of("minagri"), "u2", List.of("MINAGRI"), "u3", List.of("rdb")),
                List.of("minagri", "minisante"));
        assertThat(s.get("minagri")).isEqualTo(new ClientAccessWrites.InstitutionAccess("minagri", true, 2));
        assertThat(s.get("minisante")).isEqualTo(new ClientAccessWrites.InstitutionAccess("minisante", false, 0));
        assertThat(s).containsOnlyKeys("minagri", "minisante");
    }

    @Test
    void assignmentFromPolicies() {
        ClientAssignment a = ClientAssignment.fromPolicies(List.of(
                AccessPolicy.group("1", CanonicalPolicies.GROUPS, false, List.of("g1")),
                AccessPolicy.user("2", CanonicalPolicies.USERS, false, List.of("u1", "u2")),
                AccessPolicy.user("3", "hand-made", false, List.of("u2", "u3")),
                AccessPolicy.user("4", CanonicalPolicies.DENIED_USERS, true, List.of("x")),
                AccessPolicy.group("5", "deny-groups", true, List.of("g9")),
                new AccessPolicy("6", "broken", "user", null, Map.of("users", "{"))));
        assertThat(a.userIds()).containsExactly("u1", "u2", "u3");
        assertThat(a.groupIds()).containsExactly("g1");
        assertThat(a.deniedUserIds()).containsExactly("x");
        assertThat(a.enforced()).isTrue();
        assertThat(ClientAssignment.EMPTY.enforced()).isFalse();
    }
}
