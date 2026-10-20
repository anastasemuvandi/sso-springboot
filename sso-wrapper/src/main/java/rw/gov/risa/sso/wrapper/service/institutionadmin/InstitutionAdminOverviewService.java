package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.overview.AccessSection;
import rw.gov.risa.sso.wrapper.policy.overview.AppAccessRow;
import rw.gov.risa.sso.wrapper.policy.overview.BruteForceStatus;
import rw.gov.risa.sso.wrapper.policy.overview.LeaverRow;
import rw.gov.risa.sso.wrapper.policy.overview.LeaversSection;
import rw.gov.risa.sso.wrapper.policy.overview.LoginActivity;
import rw.gov.risa.sso.wrapper.policy.overview.LoginEvent;
import rw.gov.risa.sso.wrapper.policy.overview.LoginSection;
import rw.gov.risa.sso.wrapper.policy.overview.Overview;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupCounts;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupRow;
import rw.gov.risa.sso.wrapper.policy.overview.PasswordSetupSection;
import rw.gov.risa.sso.wrapper.policy.overview.UserCount;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionAppsService.App;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;

/**
 * {@code GET /institution-admin/overview} (spec §5.15; port of {@code InstitutionAdminService.overview}).
 * Four INDEPENDENT sections, each with {@code available}/{@code error} — a section that cannot be read
 * says so rather than reporting zero. Everything is filtered to this institution's members:
 *
 * <ol>
 *   <li>leavers still holding access (needs the IPPIS mirror);</li>
 *   <li>accounts waiting for their first password, by delivery bucket;</li>
 *   <li>login activity 24 h / 7 d, top failing users, locked out now (Keycloak events + brute-force
 *       detector, service account: realm-wide reads, filtered to members before anything leaves);</li>
 *   <li>access per application — groups ARE expanded here (intersected with the members), unlike the
 *       platform overview (needs {@code view-clients}).</li>
 * </ol>
 */
@Service
public class InstitutionAdminOverviewService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionAdminOverviewService.class);

    private final InstitutionMembers members;
    private final InstitutionAppsService apps;
    private final AccessHolders holders;
    private final InstitutionKeycloak kc;
    private final DelegatedExecution delegated;
    private final InstitutionRoster roster;
    private final MailDeliveryLog deliveries;

    public InstitutionAdminOverviewService(InstitutionMembers members, InstitutionAppsService apps,
                                           AccessHolders holders, InstitutionKeycloak kc, DelegatedExecution delegated,
                                           InstitutionRoster roster, MailDeliveryLog deliveries) {
        this.members = members;
        this.apps = apps;
        this.holders = holders;
        this.kc = kc;
        this.delegated = delegated;
        this.roster = roster;
        this.deliveries = deliveries;
    }

    public Map<String, Object> overview(InstitutionContext ctx, CallerPrincipal caller) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        boolean canClients = ctx.checkCapability(Capability.VIEW_CLIENTS).isEmpty();
        KeycloakExec exec = delegated.forCaller(caller);
        Map<String, UserRepresentation> memberMap = members.membersById(ctx, exec);

        // ---- applications, and who reaches each
        List<App> appList = new ArrayList<>();
        Map<String, Map<String, Holder>> holdersByApp = new LinkedHashMap<>();
        String appsError = null;
        if (canClients) {
            try {
                appList = apps.apps(ctx);
                AccessRevocationService.Lazy<List<String>> own =
                        new AccessRevocationService.Lazy<>(() -> members.memberGroupIds(ctx, exec));
                for (App a : appList) {
                    holdersByApp.put(a.clientId(), holders.holders(exec, a.clientId(), memberMap, a.crossCutting(), own));
                }
            } catch (RuntimeException e) {
                log.warn("overview: application access for {} failed: {}", ctx.institution(), e.getMessage());
                appsError = error(e);
            }
        }
        Map<String, List<String>> appsHeld = Overview.appsHeld(holdersByApp);

        Map<String, Integer> sessions = null;
        try {
            sessions = kc.clientSessions(delegated.serviceAccount());
        } catch (RuntimeException e) {
            log.warn("overview: client session stats failed: {}", e.getMessage());
        }
        List<AppAccessRow> rows = new ArrayList<>();
        for (App a : appList) {
            if (holdersByApp.containsKey(a.clientId())) {
                rows.add(Overview.appAccessRow(a.clientId(), a.name(), a.crossCutting(),
                        holdersByApp.get(a.clientId()), sessions == null ? null : sessions.getOrDefault(a.clientId(), 0)));
            }
        }
        AccessSection access = Overview.institutionAccessSection(canClients, appsError, rows);

        // ---- leavers
        Map<String, MemberAccount> accounts = new LinkedHashMap<>();
        memberMap.forEach((id, u) -> accounts.put(id, InstitutionViews.member(u)));
        LeaversSection leavers;
        try {
            Optional<List<RosterEmployee>> employees = roster.roster(ctx.institution());
            if (employees.isEmpty()) {
                leavers = LeaversSection.unavailable(Overview.MIRROR_NOT_CONFIGURED);
            } else {
                Map<String, AccountMatch> matched = AccessMatrix.resolveAccounts(employees.get(), accounts);
                List<LeaverRow> found = Overview.institutionLeavers(employees.get(), matched, accounts, appsHeld);
                leavers = Overview.institutionLeaversSection(found, canClients && appsError == null);
            }
        } catch (RuntimeException e) {
            log.warn("overview: leavers for {} failed: {}", ctx.institution(), e.getMessage());
            leavers = LeaversSection.unavailable(error(e));
        }

        // ---- waiting for a first password
        PasswordSetupSection passwordSetup;
        try {
            Map<String, Delivery> delivery = new LinkedHashMap<>();
            for (MemberAccount m : accounts.values()) {
                if (SetupLinkPolicy.awaitingPassword(Overview.toSetupAccount(m))) {
                    deliveries.last(m.id()).ifPresent(d -> delivery.put(m.id(), d));
                }
            }
            passwordSetup = PasswordSetupSection.of(Overview.passwordBacklog(accounts.values(), delivery,
                    Overview::employeeNumberAttribute));
        } catch (RuntimeException e) {
            log.warn("overview: set-password backlog for {} failed: {}", ctx.institution(), e.getMessage());
            passwordSetup = PasswordSetupSection.unavailable(error(e));
        }

        // ---- login activity
        Integer sessionsTotal = sessions == null ? null : Overview.institutionSessionsTotal(sessions,
                appList.stream().filter(a -> !a.crossCutting()).map(App::clientId).toList());
        LoginSection logins = logins(memberMap, sessionsTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", ctx.institution());
        out.put("generated_at", Instant.now().toString());
        out.put("members", memberMap.size());
        out.put("leavers", leaversJson(leavers));
        out.put("password_setup", passwordJson(passwordSetup));
        out.put("logins", loginsJson(logins));
        out.put("access", accessJson(access));
        return out;
    }

    private LoginSection logins(Map<String, UserRepresentation> memberMap, Integer sessionsTotal) {
        KeycloakExec sa = delegated.serviceAccount();
        List<LoginEvent> events;
        try {
            events = kc.loginEvents(sa, 7, Overview.MAX_EVENTS);
        } catch (RuntimeException e) {
            log.warn("overview: login events failed: {}", e.getMessage());
            return LoginSection.unavailable(error(e), sessionsTotal);
        }
        LoginActivity activity = Overview.loginActivity(events, System.currentTimeMillis(), memberMap.keySet());
        Map<String, BruteForceStatus> statuses = new LinkedHashMap<>();
        for (String uid : activity.lockoutCandidates()) {
            statuses.put(uid, kc.bruteForce(sa, uid));
        }
        return Overview.loginSection(activity, statuses, uid -> {
            UserRepresentation u = memberMap.get(uid);
            return u == null ? null : u.getUsername();
        }, events.size(), sessionsTotal);
    }

    private static String error(RuntimeException e) {
        if (e instanceof ApiException a) {
            return a.getMessage();
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ---- wire shapes (schema/overview.py) --------------------------------------------------

    static Map<String, Object> leaversJson(LeaversSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", s.available());
        m.put("error", s.error());
        m.put("applications_checked", s.applicationsChecked());
        m.put("total", s.total());
        m.put("truncated", s.truncated());
        m.put("items", s.items().stream().map(InstitutionAdminOverviewService::leaverJson).toList());
        return m;
    }

    static Map<String, Object> leaverJson(LeaverRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employee_number", r.employeeNumber());
        m.put("name", r.name());
        m.put("institution", r.institution());
        m.put("sub", r.sub());
        m.put("username", r.username());
        m.put("enabled", r.enabled());
        m.put("applications", r.applications());
        m.put("reasons", r.reasons().stream().map(x -> x.code()).toList());
        return m;
    }

    static Map<String, Object> passwordJson(PasswordSetupSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", s.available());
        m.put("error", s.error());
        PasswordSetupCounts c = s.counts();
        if (c == null) {
            m.put("counts", null);
        } else {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("total", c.total());
            counts.put("sent", c.sent());
            counts.put("failed", c.failed());
            counts.put("not_configured", c.notConfigured());
            counts.put("unknown", c.unknown());
            m.put("counts", counts);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PasswordSetupRow r : s.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sub", r.sub());
            row.put("username", r.username());
            row.put("email", r.email());
            row.put("enabled", r.enabled());
            row.put("employee_number", r.employeeNumber());
            row.put("setup_email", r.setupEmail());
            row.put("at", r.at());
            row.put("resendable", r.resendable());
            items.add(row);
        }
        m.put("items", items);
        return m;
    }

    static Map<String, Object> loginsJson(LoginSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", s.available());
        m.put("error", s.error());
        m.put("logins_24h", s.logins24h());
        m.put("logins_7d", s.logins7d());
        m.put("failed_24h", s.failed24h());
        m.put("failed_7d", s.failed7d());
        m.put("people_24h", s.people24h());
        m.put("people_7d", s.people7d());
        m.put("most_failures_7d", s.mostFailures7d().stream().map(InstitutionAdminOverviewService::userCount).toList());
        m.put("locked_now", s.lockedNow().stream().map(InstitutionAdminOverviewService::userCount).toList());
        m.put("truncated", s.truncated());
        m.put("active_sessions", s.activeSessions());
        return m;
    }

    private static Map<String, Object> userCount(UserCount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", u.sub());
        m.put("username", u.username());
        m.put("failures", u.failures());
        return m;
    }

    static Map<String, Object> accessJson(AccessSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", s.available());
        m.put("error", s.error());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AppAccessRow r : s.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client_id", r.clientId());
            row.put("name", r.name());
            row.put("cross_cutting", r.crossCutting());
            row.put("kind", r.kind());
            row.put("people", r.people());
            row.put("named", r.named());
            row.put("via_group", r.viaGroup());
            row.put("groups", r.groups());
            row.put("revoked", r.revoked());
            row.put("active_sessions", r.activeSessions());
            items.add(row);
        }
        m.put("items", items);
        return m;
    }
}
