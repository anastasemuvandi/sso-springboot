package rw.gov.risa.sso.wrapper.policy.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import rw.gov.risa.sso.wrapper.policy.clientaccess.UserAccessChange.Action;

/** Pure checks behind POST /authorization/users/{id}/client-access/{clientId} (test_user_client_access §4). */
class UserAccessChangeTest {

    static AccessDecision decision(boolean has, AccessReason reason, boolean direct, List<String> via, boolean denied,
                                   boolean byName, List<String> deniedGroups) {
        return new AccessDecision(has, reason, direct, via, denied, byName, deniedGroups);
    }

    static final AccessDecision GRANTED_VIA_GROUP = decision(true, AccessReason.ASSIGNED, false, List.of("g"), false, false, List.of());
    static final AccessDecision REVOKED_BY_NAME = decision(false, AccessReason.DENIED, false, List.of("g"), true, true, List.of());
    static final AccessDecision REVOKED_BY_GROUP = decision(false, AccessReason.DENIED, true, List.of(), true, false, List.of("g"));
    static final AccessDecision NOT_GRANTED = decision(false, AccessReason.NOT_ASSIGNED, false, List.of(), false, false, List.of());
    static final AccessDecision OPEN = decision(true, AccessReason.DENY_LIST_ONLY, false, List.of(), false, false, List.of());
    static final AccessDecision EXEMPT = decision(true, AccessReason.EXEMPT, false, List.of(), false, false, List.of());

    @Test
    void staleStateIs409StateChanged() {
        assertThat(UserAccessChange.check(Action.REVOKE, "granted", REVOKED_BY_NAME))
                .contains(new UserAccessChange.Refusal("state_changed", AccessState.REVOKED));
    }

    @Test
    void notApplicable() {
        assertThat(UserAccessChange.check(Action.GRANT, "granted", GRANTED_VIA_GROUP).orElseThrow().reason()).isEqualTo("not_applicable");
        assertThat(UserAccessChange.check(Action.REVOKE, "not_granted", NOT_GRANTED).orElseThrow().reason()).isEqualTo("not_applicable");
        assertThat(UserAccessChange.check(Action.GRANT, "exempt", EXEMPT).orElseThrow().reason()).isEqualTo("not_applicable");
        assertThat(UserAccessChange.check(Action.REVOKE, "exempt", EXEMPT).orElseThrow().reason()).isEqualTo("not_applicable");
    }

    @Test
    void aPersonRefusedThroughAGroupCannotBeGrantedByName() {
        assertThat(UserAccessChange.check(Action.GRANT, "revoked", REVOKED_BY_GROUP).orElseThrow().reason())
                .isEqualTo("denied_by_group");
    }

    @Test
    void allowedChanges() {
        assertThat(UserAccessChange.check(Action.GRANT, "revoked", REVOKED_BY_NAME)).isEmpty();
        assertThat(UserAccessChange.check(Action.GRANT, "not_granted", NOT_GRANTED)).isEmpty();
        assertThat(UserAccessChange.check(Action.REVOKE, "granted", GRANTED_VIA_GROUP)).isEmpty();
        assertThat(UserAccessChange.check(Action.REVOKE, "open", OPEN)).isEmpty();
    }

    @Test
    void revokeMode() {
        assertThat(UserAccessChange.revokeMode(GRANTED_VIA_GROUP)).isEqualTo(UserAccessChange.RevokeMode.UNASSIGN_AND_OR_DENY);
        assertThat(UserAccessChange.revokeMode(OPEN)).isEqualTo(UserAccessChange.RevokeMode.DENY_ENTRY);
    }

    @ParameterizedTest
    @CsvSource({"grant,GRANT,grant_user_access", "revoke,REVOKE,revoke_user_access"})
    void actionWire(String wire, Action action, String audit) {
        assertThat(Action.fromWire(wire)).contains(action);
        assertThat(UserAccessChange.auditAction(action)).isEqualTo(audit);
    }

    @Test
    void unknownActionIsRejected() {
        assertThat(Action.fromWire("delete")).isEmpty();
    }
}
