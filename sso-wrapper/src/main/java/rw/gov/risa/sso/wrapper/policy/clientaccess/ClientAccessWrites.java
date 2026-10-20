package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The access-assignment mutations of {@code KeycloakAuthorizationService} as pure
 * transformations of a {@link ClientAssignment}. Persist the result with
 * {@link CanonicalPolicies#plan}. All are additive/idempotent where the reference is.
 */
public final class ClientAccessWrites {

    private ClientAccessWrites() {
    }

    /** {@code admitUser}: lift that person's denial, then add the named grant. */
    public record AdmitResult(ClientAssignment assignment, boolean admitted, boolean liftedDenial) {
        public boolean changed() {
            return admitted || liftedDenial;
        }
    }

    /** {@code admitUsers}: one write for many people; {@code admitted} new, {@code already} named before. */
    public record AdmitManyResult(ClientAssignment assignment, List<String> admitted, List<String> already,
                                  List<String> lifted) {
    }

    public record DenyResult(ClientAssignment assignment, List<String> changedUserIds) {
    }

    /** One revocation target: the user and their DIRECT group ids. */
    public record RevokeTarget(String userId, Collection<String> groupIds) {
    }

    /**
     * Revocation outcome: named grants removed ({@code unassigned}), freshly denied,
     * {@code alreadyDenied} (on the deny-list already), {@code notAssigned} (nothing to take).
     */
    public record RevokeResult(ClientAssignment assignment, List<String> unassigned, List<String> denied,
                               List<String> alreadyDenied, List<String> notAssigned) {
    }

    public record NamedGroup(String id, String name) {
    }

    public record WithdrawResult(ClientAssignment assignment, List<NamedGroup> removedGroups,
                                 List<String> removedUserIds) {
        public boolean changed() {
            return !removedGroups.isEmpty() || !removedUserIds.isEmpty();
        }
    }

    public record InstitutionAccess(String institution, boolean groupAssigned, int users) {
    }

    /** Add one named grant and lift that person's denial (a grant supersedes a refusal). */
    public static AdmitResult admitUser(ClientAssignment current, String userId) {
        boolean lifted = current.deniedUserIds().contains(userId);
        List<String> denied = without(current.deniedUserIds(), Set.of(userId));
        if (current.userIds().contains(userId)) {
            return new AdmitResult(current.withDenied(denied), false, lifted);
        }
        List<String> users = new ArrayList<>(current.userIds());
        users.add(userId);
        return new AdmitResult(new ClientAssignment(users, current.groupIds(), denied), true, lifted);
    }

    public static AdmitManyResult admitUsers(ClientAssignment current, Collection<String> userIds) {
        List<String> wanted = ClientAssignment.dedupe(userIds);
        Set<String> wantedSet = new HashSet<>(wanted);
        List<String> lifted = current.deniedUserIds().stream().filter(wantedSet::contains).toList();
        List<String> already = wanted.stream().filter(current.userIds()::contains).toList();
        List<String> fresh = wanted.stream().filter(u -> !current.userIds().contains(u)).toList();
        List<String> users = new ArrayList<>(current.userIds());
        users.addAll(fresh);
        ClientAssignment next = new ClientAssignment(users, current.groupIds(),
                without(current.deniedUserIds(), wantedSet));
        return new AdmitManyResult(next, fresh, already, lifted);
    }

    /** Add one institution's group (additive: never replaces other institutions' groups). */
    public static ClientAssignment admitInstitution(ClientAssignment current, String groupId) {
        if (current.groupIds().contains(groupId)) {
            return current;
        }
        List<String> groups = new ArrayList<>(current.groupIds());
        groups.add(groupId);
        return current.withGroups(groups);
    }

    /**
     * {@code set_client_assignments}: replace the allow lists; everyone named by this call has
     * their deny entry lifted, users removed by the call keep theirs.
     */
    public static ClientAssignment setAssignments(ClientAssignment current, List<String> userIds,
                                                  List<String> groupIds) {
        List<String> users = ClientAssignment.dedupe(userIds);
        return new ClientAssignment(users, groupIds, without(current.deniedUserIds(), new HashSet<>(users)));
    }

    /** Add users to the deny-list; {@code changedUserIds} = newly added. */
    public static DenyResult denyUsers(ClientAssignment current, Collection<String> userIds) {
        List<String> fresh = ClientAssignment.dedupe(userIds).stream()
                .filter(u -> !current.deniedUserIds().contains(u)).toList();
        List<String> denied = new ArrayList<>(current.deniedUserIds());
        denied.addAll(fresh);
        return new DenyResult(current.withDenied(denied), fresh);
    }

    /** Lift deny entries; {@code changedUserIds} = whose denial was actually lifted. */
    public static DenyResult allowUsersAgain(ClientAssignment current, Collection<String> userIds) {
        List<String> lifting = ClientAssignment.dedupe(userIds).stream()
                .filter(current.deniedUserIds()::contains).toList();
        return new DenyResult(current.withDenied(without(current.deniedUserIds(), new HashSet<>(lifting))), lifting);
    }

    /**
     * Take one client away from several people in one pass. A named grant is removed; access
     * through an assigned group gets a deny entry (the group is their institution membership,
     * not one person's to change). Both can happen at once.
     */
    public static RevokeResult revokeUsers(ClientAssignment current, List<RevokeTarget> targets) {
        Set<String> assignedGroups = new HashSet<>(current.groupIds());
        List<String> unassign = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        List<String> notAssigned = new ArrayList<>();
        for (RevokeTarget t : targets) {
            String uid = t.userId();
            if (uid == null || uid.isEmpty()) {
                continue;
            }
            boolean direct = current.userIds().contains(uid);
            boolean viaGroup = t.groupIds() != null && t.groupIds().stream().anyMatch(assignedGroups::contains);
            if (direct) {
                unassign.add(uid);
            }
            if (viaGroup) {
                deny.add(uid);
            }
            if (!direct && !viaGroup) {
                notAssigned.add(uid);
            }
        }
        ClientAssignment next = current;
        if (!unassign.isEmpty()) {
            next = new ClientAssignment(without(current.userIds(), new HashSet<>(unassign)),
                    current.groupIds().stream().sorted().toList(), current.deniedUserIds());
        }
        List<String> denied = List.of();
        if (!deny.isEmpty()) {
            DenyResult d = denyUsers(next, deny);
            next = d.assignment();
            denied = d.changedUserIds();
        }
        Set<String> deniedSet = new HashSet<>(denied);
        List<String> alreadyDenied = deny.stream().filter(u -> !deniedSet.contains(u)).toList();
        return new RevokeResult(next, unassign, denied, alreadyDenied, notAssigned);
    }

    /**
     * Remove one institution's access, and nobody else's: its group (by id, or by name
     * case-insensitively — legacy UPPERCASE groups) and every named user whose
     * {@code institution} attribute is that institution. Users missing from
     * {@code userInstitutions} (unreadable/dangling) are left alone.
     *
     * @param groups          the assigned groups with their names
     * @param userInstitutions userId → that user's {@code institution} attribute values
     */
    public static WithdrawResult withdrawInstitution(ClientAssignment current, List<NamedGroup> groups,
                                                     Map<String, List<String>> userInstitutions,
                                                     String institution, String groupId) {
        String target = institution == null ? "" : institution.strip().toLowerCase(Locale.ROOT);
        List<NamedGroup> removedGroups = groups.stream()
                .filter(g -> (groupId != null && groupId.equals(g.id()))
                        || (g.name() == null ? "" : g.name().strip().toLowerCase(Locale.ROOT)).equals(target))
                .toList();
        Set<String> removedGroupIds = new HashSet<>();
        removedGroups.forEach(g -> {
            if (g.id() != null) {
                removedGroupIds.add(g.id());
            }
        });
        List<String> removedUsers = new ArrayList<>();
        for (String uid : current.userIds()) {
            List<String> values = userInstitutions.get(uid);
            if (values == null) {
                continue;
            }
            if (values.stream().anyMatch(v -> (v == null ? "" : v.strip().toLowerCase(Locale.ROOT)).equals(target))) {
                removedUsers.add(uid);
            }
        }
        if (removedGroupIds.isEmpty() && removedUsers.isEmpty()) {
            return new WithdrawResult(current, List.of(), List.of());
        }
        ClientAssignment next = new ClientAssignment(
                without(current.userIds(), new HashSet<>(removedUsers)),
                without(current.groupIds(), removedGroupIds),
                current.deniedUserIds());
        return new WithdrawResult(next, removedGroups, removedUsers);
    }

    /**
     * Per institution: is its group assigned, and how many named users carry it — what the
     * scope editor shows before the 409. Every requested institution is present (zeroes).
     */
    public static Map<String, InstitutionAccess> institutionAccessSummary(List<NamedGroup> assignedGroups,
                                                                          Map<String, List<String>> userInstitutions,
                                                                          Collection<String> institutions) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String s : institutions) {
            if (s != null && !s.isEmpty()) {
                wanted.put(s.strip().toLowerCase(Locale.ROOT), s);
            }
        }
        Map<String, boolean[]> group = new LinkedHashMap<>();
        Map<String, int[]> users = new LinkedHashMap<>();
        wanted.values().forEach(s -> {
            group.put(s, new boolean[1]);
            users.put(s, new int[1]);
        });
        for (NamedGroup g : assignedGroups) {
            String key = g.name() == null ? "" : g.name().strip().toLowerCase(Locale.ROOT);
            if (wanted.containsKey(key)) {
                group.get(wanted.get(key))[0] = true;
            }
        }
        for (List<String> values : userInstitutions.values()) {
            if (values == null) {
                continue;
            }
            for (String v : values) {
                String key = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
                if (wanted.containsKey(key)) {
                    users.get(wanted.get(key))[0]++;
                }
            }
        }
        Map<String, InstitutionAccess> out = new LinkedHashMap<>();
        wanted.values().forEach(s -> out.put(s, new InstitutionAccess(s, group.get(s)[0], users.get(s)[0])));
        return out;
    }

    private static List<String> without(List<String> ids, Set<String> remove) {
        LinkedHashSet<String> out = new LinkedHashSet<>(ids);
        out.removeAll(remove);
        return List.copyOf(out);
    }
}
