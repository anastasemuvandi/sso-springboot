package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;

/**
 * The pure half of {@code AccessMatrixTransferService.preview}: judge every mark of a parsed file
 * against the realm as it is now, and produce the plan to store (30 minutes) and confirm later. The
 * confirm applies THE STORED PLAN, not the file.
 *
 * <p>Whole-file refusals: another institution's file (403), a file older than 30 days (400), no
 * exported column left (400), an application that left the caller's scope (403), more than 2000
 * grants (400), a missing/odd approver (400). A bad signature is already a {@link SheetError} from
 * {@link AccessMatrixWorkbook#parse}. Per-mark refusals (not on the roster, leaver, locked row,
 * cannot create, provisioning's own: missing NID for an NID-keyed app, no email) are items.
 */
public final class ImportPreview {

    public static final Duration PLAN_TTL = Duration.ofMinutes(30);
    public static final int APPROVED_BY_MIN = 2;
    public static final int APPROVED_BY_MAX = 120;

    /** Item order: invalid, rejected, removal_ignored, create, grant, then anything else. */
    private static final List<String> ORDER = List.of(
            AccessMatrixSheet.OUTCOME_INVALID, AccessMatrixSheet.OUTCOME_REJECTED,
            AccessMatrixSheet.OUTCOME_REMOVAL_IGNORED, AccessMatrixSheet.OUTCOME_CREATE,
            AccessMatrixSheet.OUTCOME_GRANT);

    /** Summary keys, in the reference's order. */
    public static final List<String> SUMMARY_KEYS = List.of(
            AccessMatrixSheet.OUTCOME_GRANT, AccessMatrixSheet.OUTCOME_CREATE, AccessMatrixSheet.OUTCOME_ALREADY,
            AccessMatrixSheet.OUTCOME_REMOVAL_IGNORED, AccessMatrixSheet.OUTCOME_REJECTED,
            AccessMatrixSheet.OUTCOME_INVALID);

    private ImportPreview() {
    }

    /** A whole-file refusal. */
    public record Refusal(String code, int httpStatus, String message) {
        public static final String APPROVER_REQUIRED = "approved_by_required";
        public static final String OTHER_INSTITUTION = "other_institution_file";
        public static final String EXPIRED = "export_expired";
        public static final String NO_COLUMNS = "no_columns_left";
        public static final String CLIENT_OUTSIDE_SCOPE = "client_outside_scope";
        public static final String TOO_MANY_GRANTS = "too_many_grants";
    }

    /** One previewed (employee, application). {@code row} is the spreadsheet row, when known. */
    public record Item(Integer row, String employeeNumber, String name, String clientId, String outcome,
                       String reason, String message) {
    }

    /** Provisioning's dry-run verdict for one (employee, client) with no account yet. */
    public record CreationVerdict(String employeeNumber, String clientId, String outcome, String reason,
                                  String message) {
    }

    /**
     * The preview.
     *
     * @param items listed items, sorted ({@code already_has_access} is counted, not listed)
     * @param summary outcome -> count, every {@link #SUMMARY_KEYS} key present
     * @param plan the grants confirm will apply (outcomes will_grant / will_create_account)
     */
    public record Preview(List<Item> items, Map<String, Integer> summary, List<BulkItem> plan) {
    }

    /** Either a whole-file refusal or a preview. */
    public sealed interface Result permits Refused, Planned {
    }

    public record Refused(Refusal refusal) implements Result {
    }

    public record Planned(Preview preview) implements Result {
    }

    /** Collapse runs of whitespace to one space and trim (check it with {@link #approverRefusal}). */
    public static String normaliseApprover(String approvedBy) {
        return String.join(" ", (approvedBy == null ? "" : approvedBy).strip().split("\\s+")).strip();
    }

    public static Optional<Refusal> approverRefusal(String normalisedApprover) {
        int n = normalisedApprover == null ? 0 : normalisedApprover.length();
        if (n < APPROVED_BY_MIN || n > APPROVED_BY_MAX) {
            return Optional.of(new Refusal(Refusal.APPROVER_REQUIRED, 400,
                    "Say who approved these grants (the name of the person who signed off the list)."));
        }
        return Optional.empty();
    }

    /**
     * The frame checks, after parsing and before reading the realm: institution, age, columns.
     *
     * @param institution the caller's institution
     */
    public static Optional<Refusal> frameRefusal(ParsedSheet parsed, String institution, Instant now) {
        SheetMeta meta = parsed.meta();
        if (!meta.institution().equals(institution)) {
            return Optional.of(new Refusal(Refusal.OTHER_INSTITUTION, 403,
                    "This file was exported for '" + meta.institution() + "', not '" + institution + "'. "
                            + "Nothing was read from it."));
        }
        if (AccessMatrixSheet.isExpired(meta, now)) {
            String day = meta.exportedAt().length() >= 10 ? meta.exportedAt().substring(0, 10) : meta.exportedAt();
            return Optional.of(new Refusal(Refusal.EXPIRED, 400,
                    "This file was exported on " + day + ", more than " + AccessMatrixSheet.EXPORT_MAX_AGE.toDays()
                            + " days ago. Export the matrix again."));
        }
        if (parsed.clients().isEmpty()) {
            return Optional.of(new Refusal(Refusal.NO_COLUMNS, 400,
                    "None of the exported application columns are left in the file."));
        }
        return Optional.empty();
    }

    /**
     * Judge every mark.
     *
     * @param editableByClient client id -> editable, for the clients in the caller's scope; a parsed
     *     client absent here refuses the whole file (403)
     * @param roster employee number -> the institution's employee
     * @param accounts {@link AccessMatrix#resolveAccounts}
     * @param hasAccess (client id, user id) -> the token gate would admit them
     * @param mayCreate caller holds manage-members and manage-membership
     * @param dryRun provisioning's preview for employees with no account: employee number -> clients
     *     wanted, returning one verdict per (employee, client); see {@link #foldDryRun}
     */
    public static Result plan(
            ParsedSheet parsed,
            Map<String, Boolean> editableByClient,
            Map<String, RosterEmployee> roster,
            Map<String, AccountMatch> accounts,
            BiPredicate<String, String> hasAccess,
            boolean mayCreate,
            Function<Map<String, List<String>>, List<CreationVerdict>> dryRun) {
        Set<String> outside = new TreeSet<>();
        for (String c : parsed.clients()) {
            if (!editableByClient.containsKey(c)) {
                outside.add(c);
            }
        }
        if (!outside.isEmpty()) {
            return new Refused(new Refusal(Refusal.CLIENT_OUTSIDE_SCOPE, 403,
                    "Application(s) " + outside + " are not in your institution's scope."));
        }

        List<Item> items = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Problem p : parsed.problems()) {
            String message = p.message() + (p.value() != null && !p.value().isEmpty()
                    ? " (found: " + AccessMatrixSheet.pyRepr(p.value()) + ")" : "");
            add(items, counts, roster, parsed, AccessMatrixSheet.OUTCOME_INVALID, p.employeeNumber(), p.clientId(),
                    p.reason(), message, p.row(), true);
        }

        Map<String, List<String>> toCreate = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> e : parsed.marks().entrySet()) {
            String number = e.getKey();
            if (!roster.containsKey(number)) {
                for (Map.Entry<String, Boolean> m : e.getValue().entrySet()) {
                    if (m.getValue()) {
                        add(items, counts, roster, parsed, AccessMatrixSheet.OUTCOME_REJECTED, number, m.getKey(),
                                AccessMatrixSheet.REASON_NOT_IN_ROSTER, null, null, true);
                    }
                }
                continue;
            }
            AccountMatch match = accounts.getOrDefault(number, AccountMatch.NONE);
            String uid = match.userId();
            for (Map.Entry<String, Boolean> m : e.getValue().entrySet()) {
                String clientId = m.getKey();
                boolean has = uid != null && hasAccess.test(clientId, uid);
                Optional<Verdict> verdict = AccessMatrixSheet.classify(m.getValue(), has,
                        Boolean.TRUE.equals(editableByClient.get(clientId)), match.state(),
                        roster.get(number).status(), mayCreate);
                if (verdict.isEmpty()) {
                    continue;
                }
                Verdict v = verdict.get();
                if (AccessMatrixSheet.OUTCOME_CREATE.equals(v.outcome())) {
                    toCreate.computeIfAbsent(number, k -> new ArrayList<>()).add(clientId);
                    continue;
                }
                add(items, counts, roster, parsed, v.outcome(), number, clientId, v.reason(), null, null,
                        !AccessMatrixSheet.OUTCOME_ALREADY.equals(v.outcome()));
            }
        }

        if (!toCreate.isEmpty()) {
            for (CreationVerdict cv : dryRun.apply(toCreate)) {
                add(items, counts, roster, parsed, cv.outcome(), cv.employeeNumber(), cv.clientId(), cv.reason(),
                        cv.message(), null, true);
            }
        }

        List<BulkItem> plan = new ArrayList<>();
        for (Item i : items) {
            if (AccessMatrixSheet.APPLICABLE.contains(i.outcome())) {
                plan.add(new BulkItem(i.employeeNumber(), i.clientId()));
            }
        }
        if (plan.size() > AccessMatrix.MAX_BULK_ITEMS) {
            return new Refused(new Refusal(Refusal.TOO_MANY_GRANTS, 400,
                    "The file asks for " + plan.size() + " grants; at most " + AccessMatrix.MAX_BULK_ITEMS
                            + " per import. Split it."));
        }

        items.sort(Comparator.comparingInt((Item i) -> {
            int k = ORDER.indexOf(i.outcome());
            return k < 0 ? 9 : k;
        }).thenComparingInt(i -> i.row() == null ? 0 : i.row())
                .thenComparing(i -> i.clientId() == null ? "" : i.clientId()));
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (String k : SUMMARY_KEYS) {
            summary.put(k, counts.getOrDefault(k, 0));
        }
        return new Planned(new Preview(List.copyOf(items), summary, List.copyOf(plan)));
    }

    private static void add(List<Item> items, Map<String, Integer> counts, Map<String, RosterEmployee> roster,
                            ParsedSheet parsed, String outcome, String number, String clientId, String reason,
                            String message, Integer row, boolean listed) {
        counts.merge(outcome, 1, Integer::sum);
        if (!listed) {
            return;
        }
        RosterEmployee e = number == null ? null : roster.get(number);
        String name = null;
        if (e != null) {
            List<String> parts = new ArrayList<>();
            if (e.firstName() != null && !e.firstName().isEmpty()) {
                parts.add(e.firstName());
            }
            if (e.lastName() != null && !e.lastName().isEmpty()) {
                parts.add(e.lastName());
            }
            name = parts.isEmpty() ? null : String.join(" ", parts);
        }
        Integer r = row != null ? row : (number == null ? null : parsed.rows().get(number));
        items.add(new Item(r, number, name, clientId, outcome, reason,
                message != null && !message.isEmpty() ? message : AccessMatrixSheet.outcomeMessage(outcome, reason)));
    }

    /**
     * Group the no-account employees by the exact set of clients they want (sorted), chunked at
     * {@code limit} — one provisioning dry run per chunk.
     */
    public static List<Map.Entry<List<String>, List<String>>> dryRunBatches(
            Map<String, List<String>> wanted, int limit) {
        Map<List<String>, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> w : wanted.entrySet()) {
            List<String> clients = new ArrayList<>(w.getValue());
            clients.sort(Comparator.naturalOrder());
            groups.computeIfAbsent(List.copyOf(clients), k -> new ArrayList<>()).add(w.getKey());
        }
        int size = Math.max(1, limit);
        List<Map.Entry<List<String>, List<String>>> out = new ArrayList<>();
        for (Map.Entry<List<String>, List<String>> g : groups.entrySet()) {
            List<String> numbers = g.getValue();
            for (int i = 0; i < numbers.size(); i += size) {
                out.add(Map.entry(g.getKey(), List.copyOf(numbers.subList(i, Math.min(numbers.size(), i + size)))));
            }
        }
        return out;
    }

    /**
     * Fold provisioning's dry-run row for one employee into per-client verdicts: granted -> create;
     * denied -> rejected with the denial's reason (default {@code denied}); neither -> rejected with
     * the row's reason (default {@code not_provisioned}).
     *
     * @param denied client id -> (reason, message)
     */
    public static List<CreationVerdict> foldDryRun(
            String number, Collection<String> clients, Set<String> granted, Map<String, String[]> denied,
            String rowReason, String rowMessage) {
        List<CreationVerdict> out = new ArrayList<>();
        for (String c : clients) {
            if (granted.contains(c)) {
                out.add(new CreationVerdict(number, c, AccessMatrixSheet.OUTCOME_CREATE, null, null));
            } else if (denied.containsKey(c)) {
                String[] d = denied.get(c);
                String reason = d.length > 0 && d[0] != null && !d[0].isEmpty() ? d[0] : "denied";
                out.add(new CreationVerdict(number, c, AccessMatrixSheet.OUTCOME_REJECTED, reason,
                        d.length > 1 ? d[1] : null));
            } else {
                out.add(new CreationVerdict(number, c, AccessMatrixSheet.OUTCOME_REJECTED,
                        rowReason != null && !rowReason.isEmpty() ? rowReason : "not_provisioned",
                        rowMessage != null && !rowMessage.isEmpty() ? rowMessage
                                : "No account could be created for this employee."));
            }
        }
        return out;
    }
}
