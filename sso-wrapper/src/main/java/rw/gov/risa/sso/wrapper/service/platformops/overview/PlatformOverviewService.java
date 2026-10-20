package rw.gov.risa.sso.wrapper.service.platformops.overview;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.overview.AccessSection;
import rw.gov.risa.sso.wrapper.policy.overview.AppAccessRow;
import rw.gov.risa.sso.wrapper.policy.overview.BruteForceStatus;
import rw.gov.risa.sso.wrapper.policy.overview.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.overview.LeaverReason;
import rw.gov.risa.sso.wrapper.policy.overview.LeaverRow;
import rw.gov.risa.sso.wrapper.policy.overview.LeaversSection;
import rw.gov.risa.sso.wrapper.policy.overview.LoginActivity;
import rw.gov.risa.sso.wrapper.policy.overview.LoginEvent;
import rw.gov.risa.sso.wrapper.policy.overview.LoginSection;
import rw.gov.risa.sso.wrapper.policy.overview.Overview;
import rw.gov.risa.sso.wrapper.policy.overview.OverviewReport;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupSection;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;

/**
 * {@code GET /dashboard/overview}: what needs a platform admin's attention, realm-wide (port of
 * {@code services/platform_overview.py}). The same four sections as the institution overview, built
 * with the same rules ({@code policy.overview.Overview}), each INDEPENDENT with {@code available}/
 * {@code error} — a section that cannot be read says so instead of reporting zeros:
 *
 * <ol>
 *   <li><b>leavers</b> — from the mirror's realm-wide view (no open post at ANY employer), each linked
 *       account checked (enabled? in a group? reaching which clients?) up to {@link Overview#LEAVER_CHECKS};
 *       past that {@code truncated};</li>
 *   <li><b>password_setup</b> — accounts with UPDATE_PASSWORD pending, bucketed by the last set-password
 *       email delivery ({@link MailDeliveryLog}): failed / not_configured / unknown / sent;</li>
 *   <li><b>logins</b> — Keycloak LOGIN/LOGIN_ERROR events over 7 days (at most 10,000, {@code truncated}
 *       when full), who is locked out NOW per the brute-force detector (only users with a recent failure,
 *       at most 50), realm sessions;</li>
 *   <li><b>access</b> — per client: people named, groups admitted (NOT expanded), people revoked, live
 *       sessions.</li>
 * </ol>
 *
 * The whole result is cached for 60 s ({@link OverviewCache}); {@code refresh=true} rebuilds it.
 */
@Service
public class PlatformOverviewService {

    private static final Logger log = LoggerFactory.getLogger(PlatformOverviewService.class);
    static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final AccessPolicyStore policies;
    private final MailDeliveryLog deliveries;
    private final InstitutionRegistryHolder registry;
    private final ObjectProvider<IdentityStore> mirror;
    private final OverviewCache cache;
    private final List<String> exemptClients;
    private final Clock clock;

    @Autowired
    public PlatformOverviewService(KeycloakExecutors executors, KeycloakDirectory directory,
                                   AccessPolicyStore policies, MailDeliveryLog deliveries,
                                   InstitutionRegistryHolder registry, ObjectProvider<IdentityStore> mirror,
                                   OverviewCache cache, SsoProperties sso) {
        this(executors, directory, policies, deliveries, registry, mirror, cache, sso.keycloak().exemptClients(),
                Clock.systemUTC());
    }

    PlatformOverviewService(KeycloakExecutors executors, KeycloakDirectory directory, AccessPolicyStore policies,
                            MailDeliveryLog deliveries, InstitutionRegistryHolder registry,
                            ObjectProvider<IdentityStore> mirror, OverviewCache cache, List<String> exemptClients,
                            Clock clock) {
        this.executors = executors;
        this.directory = directory;
        this.policies = policies;
        this.deliveries = deliveries;
        this.registry = registry;
        this.mirror = mirror;
        this.cache = cache;
        this.exemptClients = List.copyOf(exemptClients);
        this.clock = clock;
    }

    /** The overview, from cache unless it is older than 60 s or {@code refresh}. */
    public OverviewView overview(boolean refresh) {
        if (!refresh) {
            var hit = cache.get();
            if (hit.isPresent()) {
                return hit.get();
            }
        }
        OverviewView value = OverviewView.of(build());
        cache.put(value);
        return value;
    }

    /** One fresh build. Every section is computed independently; none throws. */
    public OverviewReport build() {
        // ---- users (password backlog, naming people) --------------------------------------
        List<UserRepresentation> users = null;
        String usersError = null;
        try {
            List<UserRepresentation> all = new ArrayList<>();
            executors.serviceAccount().run(realm -> directory.forEachUserPage(realm, 200, true, page -> {
                all.addAll(page);
                return true;
            }));
            users = all;
        } catch (RuntimeException e) {
            log.warn("platform overview: user walk failed: {}", e.toString());
            usersError = err(e);
        }
        Map<String, UserRepresentation> byId = new HashMap<>();
        if (users != null) {
            users.forEach(u -> byId.put(u.getId(), u));
        }

        // ---- clients and their assignments -------------------------------------------------
        Map<String, rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment> assignments = new LinkedHashMap<>();
        List<ClientRepresentation> clients = new ArrayList<>();
        Map<String, String> groupNames = new HashMap<>();
        String accessError = null;
        try {
            executors.serviceAccount().run(realm -> {
                for (ClientRepresentation c : realm.clients().findAll()) {
                    if (Overview.excludedFromPlatformAccess(c.getClientId(), exemptClients)) {
                        continue;
                    }
                    clients.add(c);
                    try {
                        var a = rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment.fromPolicies(
                                policies.read(realm, c.getId()));
                        assignments.put(c.getClientId(), a);
                        for (String gid : a.groupIds()) {
                            groupNames.computeIfAbsent(gid, id -> groupName(realm, id));
                        }
                    } catch (RuntimeException e) {
                        log.warn("platform overview: access for {} failed: {}", c.getClientId(), e.toString());
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("platform overview: client catalogue failed: {}", e.toString());
            accessError = err(e);
        }

        Map<String, Integer> sessions = null;
        try {
            sessions = executors.serviceAccount().call(PlatformOverviewService::sessionStats);
        } catch (RuntimeException e) {
            log.warn("platform overview: session stats failed: {}", e.toString());
        }

        Set<String> known = registry.knownSlugs();
        List<AppAccessRow> rows = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        for (ClientRepresentation c : clients) {
            var a = assignments.get(c.getClientId());
            if (a == null) {
                unread.add(c.getClientId());
                continue;
            }
            ClientScope scope = ClientScope.parse(c.getClientId(), c.getAttributes(), known);
            rows.add(Overview.platformAccessRow(c.getClientId(), c.getName(),
                    scope.crossCutting(), scope.kind().code(), a.userIds().size(),
                    a.groupIds().stream().map(g -> groupNames.getOrDefault(g, g)).toList(),
                    a.deniedUserIds().size(), sessions == null ? null : sessions.getOrDefault(c.getClientId(), 0)));
        }
        AccessSection access = Overview.platformAccessSection(rows, unread, accessError);

        // ---- leavers ------------------------------------------------------------------------
        LeaversSection leavers = leavers(assignments, accessError == null);

        // ---- accounts waiting for their first password -------------------------------------
        PasswordSetupSection passwordSetup;
        if (users == null) {
            passwordSetup = PasswordSetupSection.unavailable(usersError);
        } else {
            try {
                List<MemberAccount> accounts = new ArrayList<>();
                Map<String, Delivery> delivery = new HashMap<>();
                for (UserRepresentation u : users) {
                    if (u.getRequiredActions() == null || !u.getRequiredActions().contains(UPDATE_PASSWORD)) {
                        continue;
                    }
                    MemberAccount m = member(u);
                    accounts.add(m);
                    deliveries.last(u.getId()).ifPresent(d -> delivery.put(u.getId(), d));
                }
                passwordSetup = PasswordSetupSection.of(
                        Overview.passwordBacklog(accounts, delivery, Overview::employeeNumberAttribute));
            } catch (RuntimeException e) {
                log.warn("platform overview: set-password backlog failed: {}", e.toString());
                passwordSetup = PasswordSetupSection.unavailable(err(e));
            }
        }

        // ---- login activity -------------------------------------------------------------------
        Integer sessionsTotal = Overview.realmSessionsTotal(sessions);
        LoginSection logins;
        try {
            logins = loginSection(byId, sessionsTotal);
        } catch (RuntimeException e) {
            log.warn("platform overview: login events failed: {}", e.toString());
            logins = LoginSection.unavailable(err(e), sessionsTotal);
        }

        return new OverviewReport(null,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.instant().atOffset(ZoneOffset.UTC)),
                users == null ? null : users.size(), leavers, passwordSetup, logins, access);
    }

    LoginSection loginSection(Map<String, UserRepresentation> byId, Integer sessionsTotal) {
        long now = clock.millis();
        return executors.serviceAccount().call(realm -> {
            List<EventRepresentation> events = realm.getEvents(List.of(Overview.LOGIN, Overview.LOGIN_ERROR), null,
                    null, now - 7 * Overview.DAY_MS, now, null, 0, Overview.MAX_EVENTS, "desc");
            List<LoginEvent> mapped = events.stream()
                    .map(e -> new LoginEvent(e.getType(), e.getUserId(), e.getTime(), e.getError())).toList();
            LoginActivity activity = Overview.loginActivity(mapped, now, null);
            Map<String, BruteForceStatus> statuses = new HashMap<>();
            for (String uid : activity.lockoutCandidates()) {
                try {
                    statuses.put(uid, bruteForce(realm, uid));
                } catch (RuntimeException e) {
                    log.debug("platform overview: brute-force status of {} unreadable: {}", uid, e.toString());
                }
            }
            return Overview.loginSection(activity, statuses, uid -> usernameOf(realm, byId, uid), events.size(),
                    sessionsTotal);
        });
    }

    /** {@code attack-detection/brute-force/users/{id}} → {@link BruteForceStatus}. */
    public static BruteForceStatus bruteForce(RealmResource realm, String userId) {
        Map<String, Object> m = realm.attackDetection().bruteForceUserStatus(userId);
        boolean disabled = m != null && Boolean.TRUE.equals(m.get("disabled"));
        Integer failures = m != null && m.get("numFailures") instanceof Number n ? n.intValue() : null;
        return new BruteForceStatus(disabled, failures);
    }

    private static String usernameOf(RealmResource realm, Map<String, UserRepresentation> byId, String uid) {
        UserRepresentation u = byId.get(uid);
        if (u != null) {
            return u.getUsername();
        }
        try {
            return realm.users().get(uid).toRepresentation().getUsername();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- leavers -------------------------------------------------------------------------------

    record MirrorLeaver(RosterEmployee employee, int total) {
    }

    LeaversSection leavers(Map<String, rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment> assignments,
                           boolean applicationsChecked) {
        IdentityStore store = mirror.getIfAvailable();
        if (store == null) {
            return LeaversSection.unavailable(Overview.MIRROR_NOT_CONFIGURED);
        }
        int total;
        List<RosterEmployee> found;
        try {
            total = realmLeaverCount(store);
            found = realmLeavers(store, Overview.LEAVER_CHECKS);
        } catch (RuntimeException e) {
            log.warn("platform overview: realm leavers failed: {}", e.toString());
            return LeaversSection.unavailable(err(e));
        }
        Map<String, ClientAssignment> asg = new LinkedHashMap<>();
        Map<String, Set<String>> denied = new LinkedHashMap<>();
        assignments.forEach((cid, a) -> {
            asg.put(cid, new ClientAssignment(new HashSet<>(a.userIds()), new HashSet<>(a.groupIds())));
            denied.put(cid, new HashSet<>(a.deniedUserIds()));
        });
        try {
            List<LeaverRow> rows = executors.serviceAccount().call(realm -> {
                List<LeaverRow> out = new ArrayList<>();
                for (RosterEmployee e : found) {
                    String uid = e.keycloakUserId();
                    UserRepresentation user = directory.getUser(realm, uid).orElse(null);
                    if (user == null) {
                        continue; // the account is gone — nothing left to be a risk
                    }
                    List<GroupRepresentation> groups = directory.userGroups(realm, uid);
                    Set<String> groupIds = new HashSet<>();
                    groups.forEach(g -> groupIds.add(g.getId()));
                    List<String> apps = Overview.heldApplications(uid, groupIds, asg, denied);
                    boolean enabled = !Boolean.FALSE.equals(user.isEnabled());
                    List<LeaverReason> reasons = Overview.leaverReasons(enabled, apps, !groups.isEmpty());
                    if (!reasons.isEmpty()) {
                        out.add(Overview.leaverRow(e, member(user), apps, reasons));
                    }
                }
                return out;
            });
            return Overview.platformLeaversSection(rows, total, found.size(), applicationsChecked);
        } catch (RuntimeException e) {
            log.warn("platform overview: leaver checks failed: {}", e.toString());
            return LeaversSection.unavailable(err(e));
        }
    }

    static final String LEAVER_CONDITIONS = """
            FROM employee e JOIN employee_sso_link l ON l.employee_id = e.id
            WHERE l.keycloak_user_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM employment em WHERE em.employee_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM job j JOIN employment em ON j.employment_id = em.id
                              WHERE em.employee_id = e.id AND j.end_date IS NULL)
            """;

    /** Leavers everywhere (no open job at ANY employer) whose account the mirror links. */
    static int realmLeaverCount(IdentityStore store) {
        Integer n = store.jdbc().getJdbcTemplate().queryForObject("SELECT count(*) " + LEAVER_CONDITIONS,
                Integer.class);
        return n == null ? 0 : n;
    }

    /** The first {@code limit} of them, freshest end date first; {@code institution} = where the last post ended. */
    static List<RosterEmployee> realmLeavers(IdentityStore store, int limit) {
        String sql = """
                SELECT e.id, e.first_name, e.last_name, e.email, l.keycloak_user_id,
                       (SELECT i.short_code FROM job j JOIN employment em ON j.employment_id = em.id
                          JOIN institution i ON em.institution_id = i.id
                         WHERE em.employee_id = e.id ORDER BY j.end_date DESC NULLS LAST, j.start_date DESC LIMIT 1)
                         AS institution,
                       (SELECT j.end_date FROM job j JOIN employment em ON j.employment_id = em.id
                         WHERE em.employee_id = e.id ORDER BY j.end_date DESC NULLS LAST, j.start_date DESC LIMIT 1)
                         AS left_on
                """ + LEAVER_CONDITIONS + " ORDER BY left_on DESC NULLS LAST, e.id LIMIT " + Math.max(1, limit);
        return store.jdbc().getJdbcTemplate().query(sql, (rs, i) -> new RosterEmployee(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4), null, "terminated", null, rs.getString(5),
                rs.getString(6)));
    }

    // ---- helpers ---------------------------------------------------------------------------

    static Map<String, Integer> sessionStats(RealmResource realm) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Map<String, String> s : realm.getClientSessionStats()) {
            entries.add(Map.entry(s.getOrDefault("clientId", ""), parseInt(s.get("active"))));
        }
        return Overview.sessionsByClient(entries);
    }

    static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static MemberAccount member(UserRepresentation u) {
        return new MemberAccount(u.getId(), u.getUsername(), u.getEmail(), !Boolean.FALSE.equals(u.isEnabled()),
                u.getAttributes(), u.getRequiredActions());
    }

    private String groupName(RealmResource realm, String groupId) {
        try {
            GroupRepresentation g = realm.groups().group(groupId).toRepresentation();
            return g.getName() != null ? g.getName() : groupId;
        } catch (RuntimeException e) {
            return groupId;
        }
    }

    static String err(RuntimeException e) {
        if (e instanceof jakarta.ws.rs.WebApplicationException w) {
            return KeycloakErrors.message(w, "Keycloak answered HTTP " + w.getResponse().getStatus());
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

}
