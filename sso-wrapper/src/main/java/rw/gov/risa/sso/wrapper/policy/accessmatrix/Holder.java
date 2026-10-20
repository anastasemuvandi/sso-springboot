package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.List;

/**
 * One entry of a client's holder map ({@code _client_holders}): how one member reaches one client.
 * {@code direct} = named on {@code sso-access-users}; {@code groupIds}/{@code groupNames} = groups
 * the client admits that the member is in; {@code denied} = on {@code sso-access-denied-users}.
 */
public record Holder(boolean direct, List<String> groupIds, List<String> groupNames, boolean denied) {

    public Holder {
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        groupNames = groupNames == null ? List.of() : List.copyOf(groupNames);
    }
}
