package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupAccount;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;

/**
 * The overview pages' numbers — what needs an admin's attention — with no Keycloak and no HTTP.
 * Port of {@code app/core/overview.py} plus the pure parts of {@code platform_overview.py} and
 * {@code InstitutionAdminService.overview}/{@code login_section}.
 *
 * <p>Four independent sections, each with {@code available}/{@code error}: leavers still holding
 * access; accounts waiting for their first password (by delivery bucket); login activity (24 h /
 * 7 d); access per application. Nothing here decides authorization.
 */
public final class Overview {

    /** Rows returned per list. Counts are always complete. */
    public static final int MAX_ROWS = 50;
    /** Platform overview: leavers checked individually at most. */
    public static final int LEAVER_CHECKS = 200;
    /** Platform overview cache lifetime. */
    public static final int CACHE_SECONDS = 60;
    /** Keycloak events read at most; a full page means {@code truncated}. */
    public static final int MAX_EVENTS = 10_000;

    public static final String SETUP_UNKNOWN = "unknown";

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_ERROR = "LOGIN_ERROR";
    public static final long DAY_MS = 24L * 3600 * 1000;
    /** Matches the realm's flat 900 s lockout. */
    public static final long LOCKOUT_WINDOW_MS = 15L * 60 * 1000;
    public static final int MAX_LOCKOUT_CHECKS = 50;
    public static final int TOP_FAILING = 5;

    public static final String MIRROR_NOT_CONFIGURED = "The IPPIS employee mirror is not configured.";
    public static final String NO_VIEW_CLIENTS = "Your role does not include view-clients.";

    /** Keycloak's own clients: nobody is assigned to them. Excluded from the platform access section. */
    public static final Set<String> BUILTIN_CLIENTS = Set.of(
            "account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console");

    private Overview() {
    }

    // ---- leavers -------------------------------------------------------------

    /**
     * Why one leaver's account is still a risk, or empty. A disabled account with nothing assigned is
     * offboarded whatever group it is in; an enabled one is a risk on its own; an app still held is a
     * risk even on a disabled account. Group membership is added only alongside another reason.
     */
    public static List<LeaverReason> leaverReasons(boolean enabled, Collection<String> apps, boolean inInstitution) {
        List<LeaverReason> reasons = new ArrayList<>();
        if (enabled) {
            reasons.add(LeaverReason.ACCOUNT_ENABLED);
        }
        if (apps != null && !apps.isEmpty()) {
            reasons.add(LeaverReason.HOLDS_APPLICATIONS);
        }
        if (!reasons.isEmpty() && inInstitution) {
            reasons.add(LeaverReason.STILL_IN_INSTITUTION);
        }
        return reasons;
    }

    public static LeaverRow leaverRow(
            RosterEmployee employee, MemberAccount user, List<String> apps, List<LeaverReason> reasons) {
        List<String> parts = new ArrayList<>();
        if (employee.firstName() != null && !employee.firstName().isEmpty()) {
            parts.add(employee.firstName());
        }
        if (employee.lastName() != null && !employee.lastName().isEmpty()) {
            parts.add(employee.lastName());
        }
        String name = String.join(" ", parts).strip();
        String number = employee.employeeNumber() == null ? "" : employee.employeeNumber();
        return new LeaverRow(number, name.isEmpty() ? number : name, employee.institution(), user.id(),
                user.username(), user.enabled(), apps, reasons);
    }

    /** Enabled first, then most applications, then name (case-insensitive). */
    public static final Comparator<LeaverRow> LEAVER_ORDER = Comparator
            .comparing((LeaverRow r) -> !r.enabled())
            .thenComparing(r -> -r.applications().size())
            .thenComparing(r -> r.name().toLowerCase(Locale.ROOT));

    /**
     * Leavers of ONE institution whose account is still a risk. Only accounts that are still this
     * institution's members are considered (a leaver linked elsewhere has transferred).
     *
     * @param accounts {@link AccessMatrix#resolveAccounts}
     * @param members user id -> member account
     * @param appsHeld user id -> client ids reached (see {@link #appsHeld})
     */
    public static List<LeaverRow> institutionLeavers(
            Collection<RosterEmployee> employees,
            Map<String, AccountMatch> accounts,
            Map<String, MemberAccount> members,
            Map<String, List<String>> appsHeld) {
        List<LeaverRow> rows = new ArrayList<>();
        for (RosterEmployee e : employees) {
            if (!AccessMatrix.STATUS_TERMINATED.equals(e.status())) {
                continue;
            }
            AccountMatch m = accounts.getOrDefault(e.employeeNumber(), AccountMatch.NONE);
            String uid = m.userId();
            if (m.state() != AccountState.EXISTING || uid == null || !members.containsKey(uid)) {
                continue;
            }
            MemberAccount user = members.get(uid);
            List<String> apps = List.copyOf(appsHeld.getOrDefault(uid, List.of()));
            List<LeaverReason> reasons = leaverReasons(user.enabled(), apps, true);
            if (!reasons.isEmpty()) {
                rows.add(leaverRow(e, user, apps, reasons));
            }
        }
        rows.sort(LEAVER_ORDER);
        return rows;
    }

    /**
     * Which clients one user reaches, by the token gate's rule, realm-wide: named, or in a group the
     * client admits — and not on its deny-list, which beats both. Sorted.
     */
    public static List<String> heldApplications(
            String userId, Set<String> userGroupIds,
            Map<String, ClientAssignment> assignments, Map<String, Set<String>> denied) {
        List<String> held = new ArrayList<>();
        for (Map.Entry<String, ClientAssignment> a : assignments.entrySet()) {
            if (denied.getOrDefault(a.getKey(), Set.of()).contains(userId)) {
                continue;
            }
            boolean named = a.getValue().userIds().contains(userId);
            boolean viaGroup = a.getValue().groupIds().stream().anyMatch(userGroupIds::contains);
            if (named || viaGroup) {
                held.add(a.getKey());
            }
        }
        held.sort(Comparator.naturalOrder());
        return held;
    }

    /** client id -> (user id -> holder)  =>  user id -> clients they reach (in client order). */
    public static Map<String, List<String>> appsHeld(Map<String, Map<String, Holder>> holdersByApp) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Holder>> app : holdersByApp.entrySet()) {
            for (Map.Entry<String, Holder> h : app.getValue().entrySet()) {
                if (AccessMatrix.hasAccess(h.getValue())) {
                    out.computeIfAbsent(h.getKey(), k -> new ArrayList<>()).add(app.getKey());
                }
            }
        }
        return out;
    }

    /** The institution overview's leavers section. */
    public static LeaversSection institutionLeaversSection(List<LeaverRow> rows, boolean applicationsChecked) {
        return new LeaversSection(true, null, applicationsChecked, rows.size(), false, head(rows));
    }

    /**
     * The platform overview's leavers section: {@code rows} from the checked leavers;
     * {@code truncated} when the mirror reported more leavers with accounts ({@code foundTotal}) than
     * were checked ({@code checked}).
     */
    public static LeaversSection platformLeaversSection(
            List<LeaverRow> rows, int foundTotal, int checked, boolean applicationsChecked) {
        List<LeaverRow> sorted = new ArrayList<>(rows);
        sorted.sort(LEAVER_ORDER);
        return new LeaversSection(true, null, applicationsChecked, sorted.size(), foundTotal > checked, head(sorted));
    }

    // ---- accounts waiting for their first password ---------------------------

    private static int urgency(String bucket) {
        return switch (bucket) {
            case SetupLinkPolicy.DELIVERY_FAILED -> 0;
            case SetupLinkPolicy.DELIVERY_NOT_CONFIGURED -> 1;
            case SETUP_UNKNOWN -> 2;
            default -> 3;
        };
    }

    /**
     * Accounts with {@code UPDATE_PASSWORD} pending, by how their email went. Failed / not configured
     * first (the case an admin can act on), then unknown, then sent; then username.
     *
     * @param delivery user id -> last delivery (null/absent = unknown)
     * @param employeeNumberOf null = no employee number column
     */
    public static PasswordBacklog passwordBacklog(
            Collection<MemberAccount> users,
            Map<String, Delivery> delivery,
            Function<MemberAccount, String> employeeNumberOf) {
        int total = 0;
        int sent = 0;
        int failed = 0;
        int notConfigured = 0;
        int unknown = 0;
        List<PasswordSetupRow> rows = new ArrayList<>();
        for (MemberAccount user : users) {
            SetupAccount account = toSetupAccount(user);
            if (!SetupLinkPolicy.awaitingPassword(account)) {
                continue;
            }
            String uid = user.id();
            Delivery record = uid == null ? null : delivery.get(uid);
            String status = record == null ? null : record.status();
            String bucket = status != null && SetupLinkPolicy.DELIVERY_STATUSES.contains(status) ? status : SETUP_UNKNOWN;
            total++;
            switch (bucket) {
                case SetupLinkPolicy.DELIVERY_SENT -> sent++;
                case SetupLinkPolicy.DELIVERY_FAILED -> failed++;
                case SetupLinkPolicy.DELIVERY_NOT_CONFIGURED -> notConfigured++;
                default -> unknown++;
            }
            rows.add(new PasswordSetupRow(
                    uid, user.username(), user.email(), user.enabled(),
                    employeeNumberOf == null ? null : employeeNumberOf.apply(user),
                    bucket, record == null ? null : record.at(),
                    SetupLinkPolicy.adminResendRefusal(account, record, null).isEmpty()));
        }
        rows.sort(Comparator.comparingInt((PasswordSetupRow r) -> urgency(r.setupEmail()))
                .thenComparing(r -> r.username() == null ? "" : r.username().toLowerCase(Locale.ROOT)));
        return new PasswordBacklog(new PasswordSetupCounts(total, sent, failed, notConfigured, unknown), head(rows));
    }

    /** The first non-blank {@code employee_number} attribute, stripped, or null. */
    public static String employeeNumberAttribute(MemberAccount user) {
        for (String v : user.attribute(AccessMatrix.ATTR_EMPLOYEE_NUMBER)) {
            if (v != null && !v.strip().isEmpty()) {
                return v.strip();
            }
        }
        return null;
    }

    /** The setup-link view of a member account. */
    public static SetupAccount toSetupAccount(MemberAccount user) {
        return new SetupAccount(user.id(), user.username(), user.email(), user.enabled(), user.requiredActions(),
                user.attributes());
    }

    // ---- login activity -------------------------------------------------------

    /**
     * Counts over {@code LOGIN} / {@code LOGIN_ERROR} events in the last 7 days. {@code userIds}
     * scopes them to one institution's members (null = realm-wide; a failure with no user id is then
     * counted realm-wide only). A {@code LOGIN} carrying an {@code error} counts as a failure.
     */
    public static LoginActivity loginActivity(Iterable<LoginEvent> events, long nowMs, Set<String> userIds) {
        long dayAgo = nowMs - DAY_MS;
        long weekAgo = nowMs - 7 * DAY_MS;
        int logins24h = 0;
        int logins7d = 0;
        int failed24h = 0;
        int failed7d = 0;
        Set<String> people24h = new HashSet<>();
        Set<String> people7d = new HashSet<>();
        Map<String, Integer> failuresByUser = new LinkedHashMap<>();
        Map<String, Long> lastFailure = new LinkedHashMap<>();

        for (LoginEvent ev : events) {
            long t = ev.timeMs();
            if (t < weekAgo) {
                continue;
            }
            String uid = ev.userId();
            if (userIds != null && (uid == null || !userIds.contains(uid))) {
                continue;
            }
            boolean hasError = ev.error() != null && !ev.error().isEmpty();
            boolean recent = t >= dayAgo;
            if (LOGIN.equals(ev.type()) && !hasError) {
                logins7d++;
                if (uid != null && !uid.isEmpty()) {
                    people7d.add(uid);
                }
                if (recent) {
                    logins24h++;
                    if (uid != null && !uid.isEmpty()) {
                        people24h.add(uid);
                    }
                }
            } else if (LOGIN_ERROR.equals(ev.type()) || (LOGIN.equals(ev.type()) && hasError)) {
                failed7d++;
                if (recent) {
                    failed24h++;
                }
                if (uid != null && !uid.isEmpty()) {
                    failuresByUser.merge(uid, 1, Integer::sum);
                    lastFailure.merge(uid, t, Math::max);
                }
            }
        }

        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastFailure.entrySet()) {
            if (e.getValue() >= nowMs - LOCKOUT_WINDOW_MS) {
                candidates.add(e.getKey());
            }
        }
        candidates.sort(Comparator.comparingLong((String uid) -> -lastFailure.get(uid)));

        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(failuresByUser.entrySet());
        byCount.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> -e.getValue()));
        List<UserCount> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byCount.subList(0, Math.min(TOP_FAILING, byCount.size()))) {
            top.add(new UserCount(e.getKey(), null, e.getValue()));
        }
        return new LoginActivity(logins24h, logins7d, failed24h, failed7d, people24h.size(), people7d.size(),
                top, candidates.subList(0, Math.min(MAX_LOCKOUT_CHECKS, candidates.size())));
    }

    /** Brute-force status -> locked now. Null (unreadable) is not a lockout. */
    public static boolean isLocked(BruteForceStatus status) {
        return status != null && status.disabled();
    }

    /**
     * The login section: {@link #loginActivity} plus who of the candidates is locked out now.
     *
     * @param statuses candidate user id -> brute-force status (absent/null = unreadable, not locked);
     *     the caller asks Keycloak only for {@link LoginActivity#lockoutCandidates()}
     * @param usernameOf user id -> username (may return null)
     * @param eventCount how many events Keycloak returned ({@code >= MAX_EVENTS} = truncated)
     */
    public static LoginSection loginSection(
            LoginActivity activity,
            Map<String, BruteForceStatus> statuses,
            Function<String, String> usernameOf,
            int eventCount,
            Integer sessionsTotal) {
        List<UserCount> locked = new ArrayList<>();
        for (String uid : activity.lockoutCandidates()) {
            BruteForceStatus st = statuses.get(uid);
            if (isLocked(st)) {
                locked.add(new UserCount(uid, usernameOf.apply(uid), st.numFailures()));
            }
        }
        List<UserCount> top = new ArrayList<>();
        for (UserCount u : activity.mostFailures7d()) {
            top.add(new UserCount(u.sub(), usernameOf.apply(u.sub()), u.failures()));
        }
        return new LoginSection(true, null,
                activity.logins24h(), activity.logins7d(), activity.failed24h(), activity.failed7d(),
                activity.people24h(), activity.people7d(), top, locked, eventCount >= MAX_EVENTS, sessionsTotal);
    }

    // ---- access per application -----------------------------------------------

    /** One application's reach within an institution, from its holder map. */
    public static AppAccessRow appAccessRow(
            String clientId, String name, boolean crossCutting, Map<String, Holder> holders, Integer activeSessions) {
        int people = 0;
        int named = 0;
        int viaGroup = 0;
        int revoked = 0;
        for (Holder h : holders.values()) {
            if (h != null && h.denied()) {
                revoked++;
            }
            if (AccessMatrix.hasAccess(h)) {
                people++;
                if (h.direct()) {
                    named++;
                }
                if (!h.groupIds().isEmpty()) {
                    viaGroup++;
                }
            }
        }
        return new AppAccessRow(clientId, name, crossCutting, null, people, named, viaGroup, null, revoked,
                activeSessions);
    }

    /**
     * The institution overview's access section: unavailable without view-clients, or when reading
     * the apps failed; rows sorted by most people, then client id.
     */
    public static AccessSection institutionAccessSection(boolean canViewClients, String appsError, List<AppAccessRow> rows) {
        List<AppAccessRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt((AppAccessRow r) -> -(r.people() == null ? 0 : r.people()))
                .thenComparing(r -> r.clientId() == null ? "" : r.clientId()));
        return new AccessSection(canViewClients && appsError == null, canViewClients ? appsError : NO_VIEW_CLIENTS,
                sorted);
    }

    /**
     * A platform overview row: people named on the client and the groups it admits, without
     * expanding group membership ({@code people}/{@code viaGroup} null).
     *
     * @param kind the client-scope kind (institution / cross_cutting / platform)
     */
    public static AppAccessRow platformAccessRow(
            String clientId, String name, boolean crossCutting, String kind,
            int named, List<String> groups, int revoked, Integer activeSessions) {
        return new AppAccessRow(clientId, name, crossCutting, kind, null, named, null, groups, revoked, activeSessions);
    }

    /**
     * The platform access section. Rows sorted by most sessions, most named, client id.
     * {@code catalogueError} (the client list itself failed) makes it unavailable; otherwise clients
     * whose access could not be read are named in {@code error} while the section stays available.
     */
    public static AccessSection platformAccessSection(
            List<AppAccessRow> rows, Collection<String> unreadClients, String catalogueError) {
        List<AppAccessRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt((AppAccessRow r) -> -(r.activeSessions() == null ? 0 : r.activeSessions()))
                .thenComparingInt(r -> -r.named())
                .thenComparing(r -> r.clientId() == null ? "" : r.clientId()));
        String error = catalogueError;
        if (error == null && unreadClients != null && !unreadClients.isEmpty()) {
            error = "Could not read access for: " + String.join(", ", new TreeSet<>(unreadClients));
        }
        return new AccessSection(catalogueError == null, error, sorted);
    }

    /** Clients left out of the platform access section: Keycloak built-ins plus the configured exemptions. */
    public static boolean excludedFromPlatformAccess(String clientId, Collection<String> exemptClients) {
        return clientId == null || clientId.isEmpty() || BUILTIN_CLIENTS.contains(clientId)
                || (exemptClients != null && exemptClients.contains(clientId));
    }

    /**
     * Keycloak {@code client-session-stats} entries (clientId, active) -> {@code {clientId: active}};
     * an entry without a clientId is dropped.
     */
    public static Map<String, Integer> sessionsByClient(Collection<Map.Entry<String, Integer>> stats) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (stats == null) {
            return out;
        }
        for (Map.Entry<String, Integer> s : stats) {
            if (s.getKey() != null && !s.getKey().isEmpty()) {
                out.put(s.getKey(), s.getValue() == null ? 0 : s.getValue());
            }
        }
        return out;
    }

    /** Institution overview: sessions on its OWN apps only (a shared app's sessions include others). */
    public static Integer institutionSessionsTotal(Map<String, Integer> sessions, Collection<String> ownedClientIds) {
        if (sessions == null) {
            return null;
        }
        int total = 0;
        for (String c : ownedClientIds) {
            total += sessions.getOrDefault(c, 0);
        }
        return total;
    }

    /** Platform overview: the realm's sessions, or null when the stats could not be read. */
    public static Integer realmSessionsTotal(Map<String, Integer> sessions) {
        return sessions == null ? null : sessions.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static <T> List<T> head(List<T> rows) {
        return List.copyOf(rows.subList(0, Math.min(MAX_ROWS, rows.size())));
    }
}
