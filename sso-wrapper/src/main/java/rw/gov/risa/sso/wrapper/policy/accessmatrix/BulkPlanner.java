package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pure half of {@code InstitutionAdminService.bulk_access}: request checks, the whole-request
 * 403, and the per-item concurrency guard (grant only adds, revoke only removes; a cell that changed
 * since the admin saw it is {@code rejected}/{@code state_changed}).
 *
 * <p>The service then performs one policy write per client in {@link BulkPlan.Ready#writes()},
 * provisions {@link BulkPlan.Ready#toProvision()}, and turns the write outcome into results with
 * {@link #settle} or {@link #failed}.
 */
public final class BulkPlanner {

    private BulkPlanner() {
    }

    /** Request-level checks, in reference order. {@code channel} may be any {@link Channel} code. */
    public static Optional<BulkRequestRefusal> validateRequest(String action, String channel, int itemCount) {
        if (Action.fromCode(action).isEmpty()) {
            return Optional.of(BulkRequestRefusal.INVALID_ACTION);
        }
        if (Channel.fromCode(channel).isEmpty()) {
            return Optional.of(BulkRequestRefusal.INVALID_CHANNEL);
        }
        if (itemCount <= 0) {
            return Optional.of(BulkRequestRefusal.NO_ITEMS);
        }
        if (itemCount > AccessMatrix.MAX_BULK_ITEMS) {
            return Optional.of(BulkRequestRefusal.TOO_MANY_ITEMS);
        }
        return Optional.empty();
    }

    /**
     * Judge every item.
     *
     * @param items the request's items, duplicates included
     * @param columns client id -> column, for the clients in the caller's scope (iteration order is
     *     the write order)
     * @param roster employee number -> the institution's employee
     * @param accounts {@link AccessMatrix#resolveAccounts}
     * @param holders client id -> (user id -> holder)
     * @param mayCreate the caller holds manage-members and manage-membership
     */
    public static BulkPlan plan(
            Action action,
            Collection<BulkItem> items,
            Map<String, Column> columns,
            Map<String, RosterEmployee> roster,
            Map<String, AccountMatch> accounts,
            Map<String, Map<String, Holder>> holders,
            boolean mayCreate) {
        Deduped d = AccessMatrix.dedupeItems(items);

        Set<String> outsideClients = new TreeSet<>();
        for (BulkItem it : d.unique()) {
            if (!columns.containsKey(it.clientId())) {
                outsideClients.add(String.valueOf(it.clientId()));
            }
        }
        if (!outsideClients.isEmpty()) {
            return new BulkPlan.Forbidden(BulkRequestRefusal.CLIENT_OUTSIDE_SCOPE, List.copyOf(outsideClients));
        }
        Set<String> outside = new TreeSet<>();
        for (BulkItem it : d.unique()) {
            if (!roster.containsKey(it.employeeNumber())) {
                outside.add(String.valueOf(it.employeeNumber()));
            }
        }
        if (!outside.isEmpty()) {
            return new BulkPlan.Forbidden(BulkRequestRefusal.EMPLOYEE_OUTSIDE_INSTITUTION, List.copyOf(outside));
        }

        List<ItemResult> rejected = new ArrayList<>();
        for (BulkItem dup : d.duplicates()) {
            rejected.add(reject(action, dup.employeeNumber(), sub(accounts, dup.employeeNumber()), dup.clientId(),
                    RejectionReason.DUPLICATE));
        }

        Map<String, List<PlannedWrite>> writes = new LinkedHashMap<>();
        Map<String, List<String>> toProvision = new LinkedHashMap<>();
        for (Map.Entry<String, Column> col : columns.entrySet()) {
            String clientId = col.getKey();
            Map<String, Holder> h = holders.getOrDefault(clientId, Map.of());
            List<PlannedWrite> todo = new ArrayList<>();
            for (BulkItem it : d.unique()) {
                if (!clientId.equals(it.clientId())) {
                    continue;
                }
                String number = it.employeeNumber();
                AccountMatch match = accounts.getOrDefault(number, AccountMatch.NONE);
                String uid = match.userId();
                boolean has = uid != null && AccessMatrix.hasAccess(h.get(uid));
                Optional<RejectionReason> reason = AccessMatrix.itemRejection(
                        action, has, col.getValue().editable(), match.state(), roster.get(number).status());
                if (reason.isPresent()) {
                    rejected.add(reject(action, number, uid, clientId, reason.get()));
                } else if (uid != null) {
                    todo.add(new PlannedWrite(uid, number));
                } else if (!mayCreate) {
                    rejected.add(reject(action, number, null, clientId, RejectionReason.CANNOT_CREATE_ACCOUNT));
                } else {
                    toProvision.computeIfAbsent(number, k -> new ArrayList<>()).add(clientId);
                }
            }
            if (!todo.isEmpty()) {
                writes.put(clientId, List.copyOf(todo));
            }
        }
        return new BulkPlan.Ready(List.copyOf(rejected), writes, toProvision);
    }

    /**
     * After one client's policy write: {@code done} = user ids the write admitted/already had (grant)
     * or unassigned/denied (revoke). Anyone in {@code todo} not done changed meanwhile:
     * {@code state_changed}.
     */
    public static List<ItemResult> settle(Action action, String clientId, List<PlannedWrite> todo, Set<String> done) {
        List<ItemResult> out = new ArrayList<>();
        for (PlannedWrite w : todo) {
            if (!done.contains(w.userId())) {
                out.add(reject(action, w.employeeNumber(), w.userId(), clientId, RejectionReason.STATE_CHANGED));
            } else {
                out.add(new ItemResult(w.employeeNumber(), w.userId(), clientId, ItemStatus.APPLIED, null, null));
            }
        }
        return out;
    }

    /** The whole write for a client failed: every item is {@code failed}/{@code keycloak_error}. */
    public static List<ItemResult> failed(String clientId, List<PlannedWrite> todo, String detail) {
        List<ItemResult> out = new ArrayList<>();
        for (PlannedWrite w : todo) {
            out.add(new ItemResult(w.employeeNumber(), w.userId(), clientId, ItemStatus.FAILED,
                    ItemResult.REASON_KEYCLOAK_ERROR, detail));
        }
        return out;
    }

    private static String sub(Map<String, AccountMatch> accounts, String number) {
        AccountMatch m = accounts.get(number);
        return m == null ? null : m.userId();
    }

    private static ItemResult reject(Action action, String number, String uid, String clientId, RejectionReason r) {
        return new ItemResult(number, uid, clientId, ItemStatus.REJECTED, r.code(), r.message(action));
    }
}
