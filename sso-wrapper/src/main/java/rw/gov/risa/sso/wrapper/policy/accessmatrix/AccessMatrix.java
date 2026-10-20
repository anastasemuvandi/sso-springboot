package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The employee x client access matrix — its rules, with no Keycloak and no HTTP. Port of
 * {@code app/core/access_matrix.py}.
 *
 * <p>A grant is the person named on the client's {@code sso-access-users} policy (which also lifts a
 * standing deny entry); a revoke un-names them and/or puts them on {@code sso-access-denied-users}.
 * A cell "has access" exactly when the token gate would admit them: named or admitted by a group,
 * and not denied.
 *
 * <p>Rows are employees from the IPPIS mirror, matched to accounts in memory by
 * {@link #resolveAccounts}. An account outside the institution, or one two employees resolve to,
 * locks the row.
 */
public final class AccessMatrix {

    public static final String ATTR_EMPLOYEE_NUMBER = "employee_number";
    public static final String ATTR_NATIONAL_ID = "national_id";
    public static final String STATUS_TERMINATED = "terminated";

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_BULK_ITEMS = 2000;
    public static final int MAX_COLUMNS = 25;
    public static final int NID_VISIBLE_DIGITS = 4;

    /** U+2022, the masking character. */
    public static final char MASK_CHAR = '•';

    public static final String NOT_EDITABLE_REASON =
            "Your role in this institution may view this application's users but "
                    + "not change who has access (it lacks manage-clients for it).";

    /** Row-lock messages, for the two locked account states. */
    public static final Map<AccountState, String> ROW_LOCK_MESSAGES = Map.of(
            AccountState.ELSEWHERE,
            "Linked to an SSO account outside this institution. If they transferred, "
                    + "the IPPIS sync moves it across; until then it is not yours to change.",
            AccountState.AMBIGUOUS,
            "Resolves to the same SSO account as another employee. The IPPIS records "
                    + "need correcting before access can be changed.");

    /** Gender filter value for a row IPPIS sent no gender for. */
    public static final String GENDER_UNKNOWN = "unknown";
    public static final List<String> GENDER_FILTERS = List.of("M", "F", GENDER_UNKNOWN);

    public static final String FILTER_HAS = "has";
    public static final String FILTER_NONE = "none";

    private AccessMatrix() {
    }

    /** A new batch id: 32 lowercase hex characters (uuid4 hex). */
    public static String newBatchId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Last four shown, the rest masked. Null or blank stays null (no NID on record). */
    public static String maskNid(String nid) {
        if (nid == null) {
            return null;
        }
        String value = nid.strip();
        if (value.isEmpty()) {
            return null;
        }
        int n = value.codePointCount(0, value.length());
        if (n <= NID_VISIBLE_DIGITS) {
            return String.valueOf(MASK_CHAR).repeat(n);
        }
        int cut = value.offsetByCodePoints(0, n - NID_VISIBLE_DIGITS);
        return String.valueOf(MASK_CHAR).repeat(n - NID_VISIBLE_DIGITS) + value.substring(cut);
    }

    /** The first non-blank {@code national_id} attribute value of an account, or null. */
    public static String nidOf(MemberAccount user) {
        if (user == null) {
            return null;
        }
        for (String v : user.attribute(ATTR_NATIONAL_ID)) {
            if (v != null && !v.strip().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * A {@code list_apps} row -> a matrix column.
     *
     * @param crossCutting the app is a cross-cutting (shared) client
     * @param manageable the caller's tier may manage it
     */
    public static Column columnFor(String clientId, String name, boolean crossCutting, boolean manageable) {
        ColumnKind kind = crossCutting ? ColumnKind.SHARED : ColumnKind.OWNED;
        return new Column(clientId, name, kind, manageable, manageable ? null : NOT_EDITABLE_REASON);
    }

    /** A holder entry -> what the token gate would decide. Null = not a holder. */
    public static boolean hasAccess(Holder holder) {
        return holder != null && !holder.denied();
    }

    public static Cell cell(Holder holder) {
        if (!hasAccess(holder)) {
            return Cell.NONE;
        }
        return new Cell(true, holder.direct(), holder.groupNames());
    }

    /** {@link #itemRejection(Action, boolean, boolean, AccountState, String)} for an existing account. */
    public static Optional<RejectionReason> itemRejection(Action action, boolean currentlyHasAccess, boolean editable) {
        return itemRejection(action, currentlyHasAccess, editable, AccountState.EXISTING, null);
    }

    /**
     * Why one (employee, client) change must not be applied, or empty.
     *
     * <p>Grant mode may only add access where there is none; revoke mode may only remove it where
     * there is some. A row whose account cannot be vouched for refuses both; a leaver may lose access
     * but not be given it.
     */
    public static Optional<RejectionReason> itemRejection(
            Action action, boolean currentlyHasAccess, boolean editable, AccountState account, String status) {
        if (!editable) {
            return Optional.of(RejectionReason.NOT_MANAGEABLE);
        }
        if (account != null && account.locked()) {
            return Optional.of(RejectionReason.ACCOUNT_UNRESOLVED);
        }
        if (action == Action.GRANT && STATUS_TERMINATED.equals(status) && !currentlyHasAccess) {
            return Optional.of(RejectionReason.EMPLOYEE_LEFT);
        }
        if (action == Action.GRANT && currentlyHasAccess) {
            return Optional.of(RejectionReason.STATE_CHANGED);
        }
        if (action == Action.REVOKE && !currentlyHasAccess) {
            return Optional.of(RejectionReason.STATE_CHANGED);
        }
        return Optional.empty();
    }

    /** The person-facing message for a reason code under an action; unknown codes come back as-is. */
    public static String rejectionMessage(String reasonCode, Action action) {
        return RejectionReason.fromCode(reasonCode).map(r -> r.message(action)).orElse(reasonCode);
    }

    /** Surname, first name (case-insensitive), then employee number. */
    public static final Comparator<RosterEmployee> SORT_ORDER = Comparator
            .comparing((RosterEmployee e) -> lower(e.lastName()))
            .thenComparing(e -> lower(e.firstName()))
            .thenComparing(RosterEmployee::employeeNumber);

    /**
     * Search by name, employee number, email or NID. NID matches on the digits typed, anywhere —
     * including the masked part.
     */
    public static boolean matches(RosterEmployee employee, String q) {
        if (q == null || q.isEmpty()) {
            return true;
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String hay = String.join(" ",
                nz(employee.firstName()), nz(employee.lastName()), nz(employee.employeeNumber()), nz(employee.email()))
                .toLowerCase(Locale.ROOT);
        if (hay.contains(needle)) {
            return true;
        }
        String nid = employee.nationalId();
        return nid != null && !nid.isEmpty()
                && nid.replace(" ", "").toLowerCase(Locale.ROOT).contains(needle.replace(" ", ""));
    }

    /**
     * {@code {employee_number: (state, user_id)}}, against the institution's MEMBERS only, in memory.
     *
     * <ol>
     *   <li>the mirror's link: linked to a member = theirs; linked to anyone else = ELSEWHERE;</li>
     *   <li>a member carrying the employee number as the {@code employee_number} attribute;</li>
     *   <li>a member whose username or email is the employee's email (normalised) — withheld for an
     *       address two employees share.</li>
     * </ol>
     * Two employees resolving to one account are both AMBIGUOUS. Iteration order of the result
     * follows {@code employees}.
     *
     * @param members user id -> account, the institution's members
     */
    public static Map<String, AccountMatch> resolveAccounts(
            Collection<RosterEmployee> employees, Map<String, MemberAccount> members) {
        Map<String, List<String>> byNumber = new LinkedHashMap<>();
        Map<String, List<String>> byEmail = new LinkedHashMap<>();
        for (Map.Entry<String, MemberAccount> m : members.entrySet()) {
            String uid = m.getKey();
            for (String n : m.getValue().attribute(ATTR_EMPLOYEE_NUMBER)) {
                if (norm(n) != null) {
                    byNumber.computeIfAbsent(n.strip(), k -> new ArrayList<>()).add(uid);
                }
            }
            Set<String> keys = new LinkedHashSet<>();
            String u = norm(m.getValue().username());
            String e = norm(m.getValue().email());
            if (u != null) {
                keys.add(u);
            }
            if (e != null) {
                keys.add(e);
            }
            for (String key : keys) {
                byEmail.computeIfAbsent(key, k -> new ArrayList<>()).add(uid);
            }
        }

        Map<String, Integer> emailCount = new LinkedHashMap<>();
        for (RosterEmployee e : employees) {
            String key = norm(e.email());
            if (key != null) {
                emailCount.merge(key, 1, Integer::sum);
            }
        }

        Map<String, AccountMatch> out = new LinkedHashMap<>();
        for (RosterEmployee e : employees) {
            String number = e.employeeNumber();
            String link = e.keycloakUserId();
            if (link != null && !link.isEmpty()) {
                out.put(number, members.containsKey(link) ? AccountMatch.existing(link) : AccountMatch.ELSEWHERE);
                continue;
            }
            List<String> hits = byNumber.getOrDefault(number, List.of());
            if (hits.isEmpty()) {
                String key = norm(e.email());
                if (key != null && emailCount.getOrDefault(key, 0) == 1) {
                    hits = byEmail.getOrDefault(key, List.of());
                }
            }
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(hits));
            if (distinct.size() == 1) {
                out.put(number, AccountMatch.existing(distinct.get(0)));
            } else if (distinct.size() > 1) {
                out.put(number, AccountMatch.AMBIGUOUS);
            } else {
                out.put(number, AccountMatch.NONE);
            }
        }

        Map<String, List<String>> claimed = new LinkedHashMap<>();
        for (Map.Entry<String, AccountMatch> en : out.entrySet()) {
            if (en.getValue().userId() != null) {
                claimed.computeIfAbsent(en.getValue().userId(), k -> new ArrayList<>()).add(en.getKey());
            }
        }
        for (List<String> numbers : claimed.values()) {
            if (numbers.size() > 1) {
                for (String number : numbers) {
                    out.put(number, AccountMatch.AMBIGUOUS);
                }
            }
        }
        return out;
    }

    /**
     * {@code want}: null (no filter) | "has" | "none". With {@code clientId}, about that column;
     * without, "has" = holds any listed client, "none" = holds none. Any other value = no filter.
     */
    public static boolean accessFilter(Map<String, Cell> cells, String want, String clientId) {
        if (!FILTER_HAS.equals(want) && !FILTER_NONE.equals(want)) {
            return true;
        }
        boolean held;
        if (clientId != null && !clientId.isEmpty()) {
            Cell c = cells.get(clientId);
            held = c != null && c.hasAccess();
        } else {
            held = cells.values().stream().anyMatch(Cell::hasAccess);
        }
        return FILTER_HAS.equals(want) ? held : !held;
    }

    /** {@code want}: null (no filter) | "M" | "F" | "unknown"; anything else = no filter. */
    public static boolean genderFilter(RosterEmployee employee, String want) {
        if (want == null || !GENDER_FILTERS.contains(want)) {
            return true;
        }
        String g = employee.gender() == null ? "" : employee.gender().strip().toUpperCase(Locale.ROOT);
        String gender = g.isEmpty() ? null : g;
        if (GENDER_UNKNOWN.equals(want)) {
            return gender == null;
        }
        return want.equals(gender);
    }

    /**
     * Clamp a page request. {@code pageSize} null or 0 = default; size clamped to [1, 200]; page
     * clamped to [1, pages] where pages = max(1, ceil(total/size)).
     */
    public static PageBounds pageBounds(Integer page, Integer pageSize, int total) {
        int raw = (pageSize == null || pageSize == 0) ? DEFAULT_PAGE_SIZE : pageSize;
        int size = Math.max(1, Math.min(raw, MAX_PAGE_SIZE));
        int pages = Math.max(1, (total + size - 1) / size);
        int p = (page == null || page == 0) ? 1 : page;
        p = Math.max(1, Math.min(p, pages));
        return new PageBounds(p, size, (p - 1) * size);
    }

    /** (unique, duplicates) by (employee_number, client_id), first one wins. */
    public static Deduped dedupeItems(Collection<BulkItem> items) {
        Set<BulkItem> seen = new HashSet<>();
        List<BulkItem> unique = new ArrayList<>();
        List<BulkItem> dupes = new ArrayList<>();
        for (BulkItem it : items) {
            if (!seen.add(it)) {
                dupes.add(it);
            } else {
                unique.add(it);
            }
        }
        return new Deduped(unique, dupes);
    }

    /** Counts by status; every status present (zero when none). */
    public static Map<ItemStatus, Integer> summarize(Collection<ItemStatus> statuses) {
        Map<ItemStatus, Integer> out = new EnumMap<>(ItemStatus.class);
        for (ItemStatus s : ItemStatus.values()) {
            out.put(s, 0);
        }
        for (ItemStatus s : statuses) {
            out.merge(Objects.requireNonNull(s), 1, Integer::sum);
        }
        return out;
    }

    static String norm(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
