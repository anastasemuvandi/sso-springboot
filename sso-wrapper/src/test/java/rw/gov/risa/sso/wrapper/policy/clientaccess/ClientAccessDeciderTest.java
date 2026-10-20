package rw.gov.risa.sso.wrapper.policy.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Ports tests/e2e/test_user_client_access.py §1 ("the rule") and src/tests/test_client_access.py
 * (the gate's outcomes) onto the pure decider.
 */
class ClientAccessDeciderTest {

    static AccessPolicy userPolicy(String name, List<String> users, boolean negative) {
        return AccessPolicy.user("pol-" + name, name, negative, users);
    }

    static AccessPolicy groupPolicy(String name, List<String> groups, boolean negative) {
        return AccessPolicy.group("pol-" + name, name, negative, groups);
    }

    static AccessDecision decide(List<AccessPolicy> policies, String user, List<String> groups, boolean exempt,
                                 boolean defaultDeny) {
        return ClientAccessDecider.decide(user, groups, ClientPolicies.parse(policies), exempt, defaultDeny);
    }

    static AccessDecision decide(List<AccessPolicy> policies) {
        return decide(policies, "alice", List.of("gid-a"), false, true);
    }

    @Nested
    class TheRule {
        @Test
        void exemptClientAdmitsEveryone() {
            AccessDecision d = decide(List.of(), "alice", List.of(), true, true);
            assertThat(d.hasAccess()).isTrue();
            assertThat(d.state()).isEqualTo(AccessState.EXEMPT);
            assertThat(d.reason()).isEqualTo(AccessReason.EXEMPT);
        }

        @Test
        void exemptBeatsEvenADenyEntry() {
            AccessDecision d = decide(List.of(userPolicy("sso-access-denied-users", List.of("alice"), true)),
                    "alice", List.of(), true, true);
            assertThat(d.state()).isEqualTo(AccessState.EXEMPT);
        }

        @Test
        void noPolicyFollowsTheRealmStance() {
            assertThat(decide(List.of(), "alice", List.of("gid-a"), false, true).state()).isEqualTo(AccessState.NOT_GRANTED);
            AccessDecision open = decide(List.of(), "alice", List.of("gid-a"), false, false);
            assertThat(open.hasAccess()).isTrue();
            assertThat(open.reason()).isEqualTo(AccessReason.NO_POLICY);
            assertThat(open.state()).isEqualTo(AccessState.OPEN);
        }

        @Test
        void namedGrant() {
            AccessDecision d = decide(List.of(userPolicy("sso-access-users", List.of("alice"), false)));
            assertThat(d.hasAccess()).isTrue();
            assertThat(d.direct()).isTrue();
            assertThat(d.state()).isEqualTo(AccessState.GRANTED);
            assertThat(d.reason()).isEqualTo(AccessReason.ASSIGNED);
        }

        @Test
        void groupGrant() {
            AccessDecision d = decide(List.of(groupPolicy("sso-access-groups", List.of("gid-a"), false)));
            assertThat(d.hasAccess()).isTrue();
            assertThat(d.viaGroupIds()).containsExactly("gid-a");
            assertThat(d.direct()).isFalse();
            assertThat(d.state()).isEqualTo(AccessState.GRANTED);
        }

        @Test
        void departmentMemberIsNotAdmittedByTheMinistryGroup() {
            AccessDecision d = decide(List.of(groupPolicy("sso-access-groups", List.of("gid-a"), false)),
                    "alice", List.of("gid-a-plan"), false, true);
            assertThat(d.hasAccess()).isFalse();
            assertThat(d.state()).isEqualTo(AccessState.NOT_GRANTED);
            assertThat(d.reason()).isEqualTo(AccessReason.NOT_ASSIGNED);
        }

        @Test
        void theDenyEntryBeatsTheGroupGrant() {
            AccessDecision d = decide(List.of(
                    groupPolicy("sso-access-groups", List.of("gid-a"), false),
                    userPolicy("sso-access-denied-users", List.of("alice"), true)));
            assertThat(d.hasAccess()).isFalse();
            assertThat(d.denied()).isTrue();
            assertThat(d.deniedByName()).isTrue();
            assertThat(d.viaGroupIds()).containsExactly("gid-a");
            assertThat(d.state()).isEqualTo(AccessState.REVOKED);
            assertThat(d.reason()).isEqualTo(AccessReason.DENIED);
        }

        @Test
        void theDenyEntryBeatsTheNamedGrant() {
            AccessDecision d = decide(List.of(
                    userPolicy("sso-access-users", List.of("alice"), false),
                    userPolicy("sso-access-denied-users", List.of("alice"), true)));
            assertThat(d.state()).isEqualTo(AccessState.REVOKED);
            assertThat(d.direct()).isTrue();
        }

        @Test
        void aDeniedGroup() {
            AccessDecision d = decide(List.of(
                    userPolicy("sso-access-users", List.of("alice"), false),
                    groupPolicy("deny-groups", List.of("gid-a"), true)));
            assertThat(d.state()).isEqualTo(AccessState.REVOKED);
            assertThat(d.deniedGroupIds()).containsExactly("gid-a");
            assertThat(d.deniedByName()).isFalse();
        }

        @Test
        void someoneElsesDenyEntryDoesNotTouchThisPerson() {
            AccessDecision d = decide(List.of(
                    groupPolicy("sso-access-groups", List.of("gid-a"), false),
                    userPolicy("sso-access-denied-users", List.of("bob"), true)));
            assertThat(d.state()).isEqualTo(AccessState.GRANTED);
        }

        @Test
        void notAssigned() {
            AccessDecision d = decide(List.of(userPolicy("sso-access-users", List.of("bob"), false)));
            assertThat(d.hasAccess()).isFalse();
            assertThat(d.reason()).isEqualTo(AccessReason.NOT_ASSIGNED);
        }

        @Test
        void denyListOnlyAdmitsEveryoneElse() {
            AccessDecision d = decide(List.of(userPolicy("sso-access-denied-users", List.of("bob"), true)));
            assertThat(d.hasAccess()).isTrue();
            assertThat(d.reason()).isEqualTo(AccessReason.DENY_LIST_ONLY);
            assertThat(d.state()).isEqualTo(AccessState.OPEN);
        }

        @Test
        void anEmptyPolicyNeverGrantsEveryone() {
            AccessDecision d = decide(List.of(userPolicy("sso-access-users", List.of(), false)));
            assertThat(d.hasAccess()).isFalse();
            assertThat(d.reason()).isEqualTo(AccessReason.EMPTY_POLICIES);
            assertThat(decide(List.of(userPolicy("sso-access-users", List.of(), false)), "alice", List.of(), false, false)
                    .hasAccess()).isTrue();
        }

        @Test
        void malformedConfigIsSkippedNotFatal() {
            AccessPolicy broken = new AccessPolicy(null, null, "user", "POSITIVE", Map.of("users", "{not json"));
            AccessDecision d = decide(List.of(broken, userPolicy("sso-access-users", List.of("alice"), false)));
            assertThat(d.state()).isEqualTo(AccessState.GRANTED);
        }

        @Test
        void aMalformedPolicyAloneCountsAsEmptyNotOpen() {
            AccessPolicy broken = new AccessPolicy(null, null, "group", null, Map.of("groups", "[[["));
            AccessDecision d = decide(List.of(broken));
            assertThat(d.reason()).isEqualTo(AccessReason.EMPTY_POLICIES);
            assertThat(d.hasAccess()).isFalse();
        }

        @Test
        void nonUserGroupPoliciesAreIgnored() {
            AccessDecision d = decide(List.of(new AccessPolicy("r", "role", "role", null, Map.of("roles", "[]"))));
            assertThat(d.reason()).isEqualTo(AccessReason.NO_POLICY);
        }

        @Test
        void policiesAreReadByTypeNotName() {
            // A hand-named policy still counts: the name is irrelevant to the rule.
            AccessDecision d = decide(List.of(groupPolicy("legacy-ministry-access", List.of("gid-a"), false)));
            assertThat(d.state()).isEqualTo(AccessState.GRANTED);
            AccessDecision denied = decide(List.of(
                    groupPolicy("sso-access-groups", List.of("gid-a"), false),
                    userPolicy("whatever", List.of("alice"), true)));
            assertThat(denied.state()).isEqualTo(AccessState.REVOKED);
        }

        @Test
        void nullLogicIsPositive() {
            AccessPolicy p = new AccessPolicy("x", "x", "user", null, Map.of("users", "[\"alice\"]"));
            assertThat(decide(List.of(p)).state()).isEqualTo(AccessState.GRANTED);
        }

        @Test
        void viaGroupsAndDeniedGroupsAreSorted() {
            AccessDecision d = decide(List.of(groupPolicy("g", List.of("z", "b", "m"), false)),
                    "alice", List.of("z", "b", "q"), false, true);
            assertThat(d.viaGroupIds()).containsExactly("b", "z");
        }

        @Test
        void nullAndBlankGroupIdsAreIgnored() {
            AccessDecision d = decide(List.of(groupPolicy("g", List.of("gid-a"), false)),
                    "alice", java.util.Arrays.asList(null, "", "gid-a"), false, true);
            assertThat(d.hasAccess()).isTrue();
        }
    }

    /** src/tests/test_client_access.py — the token gate's outcomes. */
    @Nested
    class Gate {
        List<String> groups = List.of("g-min");

        AccessDecision gate(String clientId, List<AccessPolicy> policies, boolean defaultDeny) {
            return ClientAccessDecider.decide("u-123", groups, clientId, policies,
                    ClientAccessExemptions.DEFAULT_EXEMPT_CLIENTS, defaultDeny);
        }

        @Test
        void exemptClientAlwaysGranted() {
            assertThat(gate("sso-client", List.of(), true).hasAccess()).isTrue();
            assertThat(gate("sso-admin-ui", List.of(userPolicy("d", List.of("u-123"), true)), true).hasAccess()).isTrue();
        }

        @Test
        void noPolicyDeniesUnderDefaultDeny() {
            assertThat(gate("rdb-portal", List.of(), true).hasAccess()).isFalse();
        }

        @Test
        void groupAllowUserInGroup() {
            assertThat(gate("kubaka-web", List.of(groupPolicy("g", List.of("g-min"), false)), true).hasAccess()).isTrue();
        }

        @Test
        void groupAllowUserNotInGroup() {
            assertThat(gate("rdb-portal", List.of(groupPolicy("g", List.of("g-rdb"), false)), true).hasAccess()).isFalse();
        }

        @Test
        void userAllowListed() {
            assertThat(gate("rdb-portal", List.of(userPolicy("u", List.of("u-123"), false)), true).hasAccess()).isTrue();
        }

        @Test
        void userAllowOtherUser() {
            assertThat(gate("rdb-portal", List.of(userPolicy("u", List.of("u-999"), false)), true).hasAccess()).isFalse();
        }

        @Test
        void denyPolicyOnUsersGroup() {
            assertThat(gate("kubaka-web", List.of(groupPolicy("g", List.of("g-min"), true)), true).hasAccess()).isFalse();
        }

        @Test
        void denyWinsOverAllow() {
            assertThat(gate("kubaka-web", List.of(groupPolicy("g", List.of("g-min"), false),
                    userPolicy("u", List.of("u-123"), true)), true).hasAccess()).isFalse();
        }

        @Test
        void emptyPolicyNeverGrantsEveryone() {
            assertThat(gate("rdb-portal", List.of(groupPolicy("g", List.of(), false)), true).hasAccess()).isFalse();
        }

        @Test
        void killSwitchOpensUnconfiguredClients() {
            assertThat(gate("rdb-portal", List.of(), false).hasAccess()).isTrue();
        }
    }

    @Test
    void exemptions() {
        assertThat(ClientAccessExemptions.isExempt("realm-management", ClientAccessExemptions.DEFAULT_EXEMPT_CLIENTS)).isTrue();
        assertThat(ClientAccessExemptions.isExempt("minagri-web", ClientAccessExemptions.DEFAULT_EXEMPT_CLIENTS)).isFalse();
        assertThat(ClientAccessExemptions.isExempt(null, ClientAccessExemptions.DEFAULT_EXEMPT_CLIENTS)).isFalse();
        assertThat(ClientAccessExemptions.isExempt("x", null)).isFalse();
        assertThat(ClientAccessExemptions.isKeycloakBuiltIn("broker")).isTrue();
        assertThat(ClientAccessExemptions.isKeycloakBuiltIn("sso-admin-ui")).isFalse();
    }

    @Test
    void stateAndReasonWireStrings() {
        assertThat(AccessState.GRANTABLE).containsExactlyInAnyOrder(AccessState.NOT_GRANTED, AccessState.REVOKED);
        assertThat(AccessState.REVOCABLE).containsExactlyInAnyOrder(AccessState.GRANTED, AccessState.OPEN);
        assertThat(AccessState.UNKNOWN.wire()).isEqualTo("unknown");
        assertThat(AccessState.fromWire("not_granted")).contains(AccessState.NOT_GRANTED);
        assertThat(AccessState.fromWire("nope")).isEmpty();
        assertThat(AccessReason.DENY_LIST_ONLY.wire()).isEqualTo("deny_list_only");
    }
}
