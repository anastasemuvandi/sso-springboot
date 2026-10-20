package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.Set;

/** A client's {@code get_client_assignments}: user ids named, and group ids admitted. */
public record ClientAssignment(Set<String> userIds, Set<String> groupIds) {

    public ClientAssignment {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }
}
