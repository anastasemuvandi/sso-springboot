package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/**
 * Section 4 row: one application's reach. Institution overview: {@code people}/{@code viaGroup}
 * set, {@code kind}/{@code groups} null. Platform overview: {@code people}/{@code viaGroup} null
 * (groups are not expanded realm-wide), {@code kind} and {@code groups} set.
 */
public record AppAccessRow(
        String clientId, String name, boolean crossCutting, String kind,
        Integer people, int named, Integer viaGroup, List<String> groups, int revoked, Integer activeSessions) {

    public AppAccessRow {
        groups = groups == null ? null : List.copyOf(groups);
    }
}
