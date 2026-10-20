package rw.gov.risa.sso.wrapper.policy.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.Op;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.PolicyChange;

class CanonicalPoliciesTest {

    @Test
    void createsPoliciesOnAClientThatHasNone() {
        List<PolicyChange> plan = CanonicalPolicies.plan(List.of(),
                new ClientAssignment(List.of("u1"), List.of("g1"), List.of("d1")));
        assertThat(plan).extracting(PolicyChange::op).containsExactly(Op.CREATE, Op.CREATE, Op.CREATE);
        assertThat(plan.get(0).body().name()).isEqualTo("sso-access-groups");
        assertThat(plan.get(0).body().groupIds()).containsExactly("g1");
        assertThat(plan.get(1).body().name()).isEqualTo("sso-access-users");
        assertThat(plan.get(1).body().logic()).isEqualTo("POSITIVE");
        assertThat(plan.get(2).body().name()).isEqualTo("sso-access-denied-users");
        assertThat(plan.get(2).body().logic()).isEqualTo("NEGATIVE");
        assertThat(plan.get(2).body().decisionStrategy()).isEqualTo("UNANIMOUS");
        assertThat(CanonicalPolicies.needsAuthorizationServices(plan)).isTrue();
    }

    @Test
    void updatesInPlaceAndDeletesEmptiedPolicies() {
        List<AccessPolicy> current = List.of(
                AccessPolicy.group("pg", CanonicalPolicies.GROUPS, false, List.of("g1")),
                AccessPolicy.user("pu", CanonicalPolicies.USERS, false, List.of("u1")),
                AccessPolicy.user("pd", CanonicalPolicies.DENIED_USERS, true, List.of("d1")));
        List<PolicyChange> plan = CanonicalPolicies.plan(current,
                new ClientAssignment(List.of("u1", "u2"), List.of("g1"), List.of()));
        assertThat(plan).containsExactly(
                new PolicyChange(Op.UPDATE, "pu", new CanonicalPolicies.PolicyBody("sso-access-users", "user", "POSITIVE",
                        "UNANIMOUS", List.of("u1", "u2"), List.of())),
                new PolicyChange(Op.DELETE, "pd", null));
        assertThat(CanonicalPolicies.needsAuthorizationServices(List.of(new PolicyChange(Op.DELETE, "x", null)))).isFalse();
    }

    @Test
    void noChangeNoWrites() {
        List<AccessPolicy> current = List.of(AccessPolicy.user("pu", CanonicalPolicies.USERS, false, List.of("u1")));
        assertThat(CanonicalPolicies.plan(current, ClientAssignment.fromPolicies(current))).isEmpty();
        assertThat(CanonicalPolicies.plan(List.of(), ClientAssignment.EMPTY)).isEmpty();
    }

    @Test
    void aHandMadePolicyIsNeverOverwrittenTheCanonicalOneIsCreated() {
        List<AccessPolicy> current = List.of(AccessPolicy.user("hm", "legacy-users", false, List.of("u1")));
        List<PolicyChange> plan = CanonicalPolicies.plan(current, new ClientAssignment(List.of("u1"), List.of(), List.of()));
        assertThat(plan).singleElement().satisfies(c -> {
            assertThat(c.op()).isEqualTo(Op.CREATE);
            assertThat(c.existingId()).isNull();
        });
    }
}
