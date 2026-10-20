package rw.gov.risa.sso.wrapper.policy.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;

class OverviewTest {

    static final long NOW = 1_800_000_000_000L;
    static final long MIN = 60_000L;
    static final long DAY = Overview.DAY_MS;

    static RosterEmployee emp(String number, String status) {
        return new RosterEmployee(number, "A", "B", null, null, status, null, null, "minagri");
    }

    static MemberAccount user(String id, String username, boolean enabled) {
        return new MemberAccount(id, username, null, enabled, Map.of());
    }

    @Nested
    class Leavers {
        @Test
        void reasons() {
            assertThat(Overview.leaverReasons(false, List.of(), false)).isEmpty();
            assertThat(Overview.leaverReasons(true, List.of(), false)).containsExactly(LeaverReason.ACCOUNT_ENABLED);
            assertThat(Overview.leaverReasons(false, List.of("x"), false)).containsExactly(LeaverReason.HOLDS_APPLICATIONS);
            // Group membership alone is not a risk on a disabled, app-less account.
            assertThat(Overview.leaverReasons(false, List.of(), true)).isEmpty();
            assertThat(Overview.leaverReasons(true, List.of(), true)).contains(LeaverReason.STILL_IN_INSTITUTION);
            assertThat(Overview.leaverReasons(true, List.of("x"), true)).containsExactly(
                    LeaverReason.ACCOUNT_ENABLED, LeaverReason.HOLDS_APPLICATIONS, LeaverReason.STILL_IN_INSTITUTION);
            assertThat(LeaverReason.HOLDS_APPLICATIONS.code()).isEqualTo("holds_applications");
            assertThat(LeaverReason.ACCOUNT_ENABLED.text()).isEqualTo("Account is still enabled");
        }

        @Test
        void institution_leavers() {
            List<RosterEmployee> employees = List.of(
                    emp("E1", "terminated"),  // enabled member -> flagged
                    emp("E2", "terminated"),  // disabled, holds an app -> flagged
                    emp("E3", "terminated"),  // disabled, nothing -> offboarded
                    emp("E4", "active"),      // active -> never
                    emp("E5", "terminated"),  // account elsewhere -> not ours
                    emp("E6", "terminated")); // no account
            Map<String, AccountMatch> accounts = Map.of(
                    "E1", AccountMatch.existing("u1"), "E2", AccountMatch.existing("u2"),
                    "E3", AccountMatch.existing("u3"), "E4", AccountMatch.existing("u4"),
                    "E5", AccountMatch.ELSEWHERE, "E6", AccountMatch.NONE);
            Map<String, MemberAccount> members = Map.of(
                    "u1", user("u1", "one", true), "u2", user("u2", "two", false),
                    "u3", user("u3", "three", false), "u4", user("u4", "four", true));
            List<LeaverRow> rows = Overview.institutionLeavers(employees, accounts, members,
                    Map.of("u2", List.of("minagri-portal"), "u4", List.of("x")));
            assertThat(rows).extracting(LeaverRow::employeeNumber).containsExactly("E1", "E2"); // enabled first
            assertThat(rows.get(1).applications()).containsExactly("minagri-portal");
            assertThat(rows.get(1).reasons()).contains(LeaverReason.HOLDS_APPLICATIONS);
            assertThat(rows.get(0).sub()).isEqualTo("u1");
            assertThat(rows.get(0).name()).isEqualTo("A B");
            assertThat(rows.get(0).reasons()).contains(LeaverReason.STILL_IN_INSTITUTION);
        }

        @Test
        void a_nameless_row_is_named_by_its_number_and_order_is_apps_then_name() {
            RosterEmployee nameless = new RosterEmployee("E9", null, null, null, null, "terminated", null, null, null);
            LeaverRow row = Overview.leaverRow(nameless, user("u", "x", true), List.of(), List.of(LeaverReason.ACCOUNT_ENABLED));
            assertThat(row.name()).isEqualTo("E9");

            List<LeaverRow> rows = new ArrayList<>(List.of(
                    new LeaverRow("1", "zed", null, "a", null, true, List.of(), List.of()),
                    new LeaverRow("2", "Amy", null, "b", null, true, List.of(), List.of()),
                    new LeaverRow("3", "bob", null, "c", null, true, List.of("x", "y"), List.of()),
                    new LeaverRow("4", "aaa", null, "d", null, false, List.of("x", "y", "z"), List.of())));
            rows.sort(Overview.LEAVER_ORDER);
            assertThat(rows).extracting(LeaverRow::employeeNumber).containsExactly("3", "2", "1", "4");
        }

        @Test
        void held_applications_respects_the_deny_list() {
            Map<String, ClientAssignment> assignments = new LinkedHashMap<>();
            assignments.put("a", new ClientAssignment(Set.of("u"), Set.of()));
            assignments.put("b", new ClientAssignment(Set.of(), Set.of("g1")));
            assignments.put("c", new ClientAssignment(Set.of("u"), Set.of()));
            assignments.put("d", new ClientAssignment(Set.of(), Set.of("other")));
            assertThat(Overview.heldApplications("u", Set.of("g1"), assignments, Map.of("c", Set.of("u"))))
                    .containsExactly("a", "b");
        }

        @Test
        void apps_held_from_holders() {
            Map<String, Map<String, Holder>> by = new LinkedHashMap<>();
            by.put("p", Map.of("u1", new Holder(true, List.of(), List.of(), false),
                    "u2", new Holder(true, List.of(), List.of(), true)));
            by.put("q", Map.of("u1", new Holder(false, List.of("g"), List.of(), false)));
            Map<String, List<String>> held = Overview.appsHeld(by);
            assertThat(held).containsOnlyKeys("u1");
            assertThat(held.get("u1")).containsExactly("p", "q");
        }

        @Test
        void sections_cap_items_at_50_but_count_all() {
            List<LeaverRow> rows = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                rows.add(new LeaverRow("E" + i, "n" + i, null, "u" + i, null, true, List.of(), List.of()));
            }
            LeaversSection s = Overview.institutionLeaversSection(rows, false);
            assertThat(s.total()).isEqualTo(60);
            assertThat(s.items()).hasSize(50);
            assertThat(s.applicationsChecked()).isFalse();
            assertThat(s.available()).isTrue();

            LeaversSection p = Overview.platformLeaversSection(rows.subList(0, 3), 250, 200, true);
            assertThat(p.truncated()).isTrue();
            assertThat(Overview.platformLeaversSection(rows.subList(0, 3), 200, 200, true).truncated()).isFalse();

            LeaversSection u = LeaversSection.unavailable(Overview.MIRROR_NOT_CONFIGURED);
            assertThat(u.available()).isFalse();
            assertThat(u.error()).isEqualTo("The IPPIS employee mirror is not configured.");
            assertThat(u.total()).isZero();
        }
    }

    @Nested
    class PasswordBacklog_ {
        @Test
        void counts_and_order() {
            List<String> waiting = List.of("UPDATE_PASSWORD");
            List<MemberAccount> users = List.of(
                    new MemberAccount("a", "a", "x@y", true, Map.of(), waiting),
                    new MemberAccount("b", "b", "x@y", true, Map.of(), waiting),
                    new MemberAccount("c", "c", "x@y", true, Map.of(), waiting),
                    new MemberAccount("d", "d", "x@y", true, Map.of("employee_number", List.of("E9")), waiting),
                    new MemberAccount("e", "e", null, true, Map.of(), List.of())); // not waiting
            Map<String, Delivery> delivery = new HashMap<>();
            delivery.put("a", new Delivery("sent", "provisioning", "t1"));
            delivery.put("b", new Delivery("failed", "provisioning", "t2"));
            delivery.put("c", null);
            delivery.put("d", new Delivery("not_configured", "provisioning", null));
            PasswordBacklog out = Overview.passwordBacklog(users, delivery, Overview::employeeNumberAttribute);
            assertThat(out.counts()).isEqualTo(new PasswordSetupCounts(4, 1, 1, 1, 1));
            assertThat(out.items()).extracting(PasswordSetupRow::sub).containsExactly("b", "d", "c", "a");
            assertThat(out.items().get(0).resendable()).isTrue();
            assertThat(out.items().get(0).at()).isEqualTo("t2");
            assertThat(out.items().get(3).resendable()).isFalse(); // delivered: the holder's to resend
            assertThat(out.items().get(2).resendable()).isFalse(); // unknown: not a failure
            assertThat(out.items().get(1).employeeNumber()).isEqualTo("E9");
            assertThat(out.items().get(2).setupEmail()).isEqualTo("unknown");
        }

        @Test
        void an_odd_status_is_unknown_and_no_employee_number_function_is_null() {
            List<MemberAccount> users = List.of(new MemberAccount("a", "a", "x@y", true, Map.of(), List.of("UPDATE_PASSWORD")));
            PasswordBacklog out = Overview.passwordBacklog(users, Map.of("a", new Delivery("bounced", "x", null)), null);
            assertThat(out.counts().unknown()).isEqualTo(1);
            assertThat(out.items().get(0).employeeNumber()).isNull();
        }

        @Test
        void a_disabled_waiting_account_is_counted_but_not_resendable() {
            List<MemberAccount> users = List.of(new MemberAccount("a", "a", "x@y", false, Map.of(), List.of("UPDATE_PASSWORD")));
            PasswordBacklog out = Overview.passwordBacklog(users, Map.of("a", new Delivery("failed", "x", null)), null);
            assertThat(out.counts().total()).isEqualTo(1);
            assertThat(out.items().get(0).resendable()).isFalse();
        }

        @Test
        void employee_number_attribute() {
            assertThat(Overview.employeeNumberAttribute(new MemberAccount("a", "a", null, true,
                    Map.of("employee_number", List.of("  ", " E7 "))))).isEqualTo("E7");
            assertThat(Overview.employeeNumberAttribute(new MemberAccount("a", "a", null, true, Map.of()))).isNull();
        }

        @Test
        void section_shapes() {
            assertThat(PasswordSetupSection.unavailable("boom").counts()).isNull();
            PasswordBacklog b = Overview.passwordBacklog(List.of(), Map.of(), null);
            PasswordSetupSection s = PasswordSetupSection.of(b);
            assertThat(s.available()).isTrue();
            assertThat(s.counts()).isEqualTo(new PasswordSetupCounts(0, 0, 0, 0, 0));
        }
    }

    @Nested
    class LoginActivity_ {
        List<LoginEvent> events() {
            return List.of(
                    new LoginEvent("LOGIN", "m1", NOW - MIN, null),
                    new LoginEvent("LOGIN", "m1", NOW - 2 * DAY, null),
                    new LoginEvent("LOGIN", "m2", NOW - 3 * DAY, null),
                    new LoginEvent("LOGIN_ERROR", "m2", NOW - 5 * MIN, "invalid_user_credentials"),
                    new LoginEvent("LOGIN_ERROR", "m2", NOW - 6 * MIN, "invalid_user_credentials"),
                    new LoginEvent("LOGIN_ERROR", "m1", NOW - 2 * DAY, "invalid_user_credentials"),
                    new LoginEvent("LOGIN", "outsider", NOW - MIN, null),
                    new LoginEvent("LOGIN_ERROR", null, NOW - MIN, "user_not_found"), // no userId
                    new LoginEvent("LOGIN", "m1", NOW - 9 * DAY, null)); // outside 7 days
        }

        @Test
        void scoped_to_members() {
            LoginActivity out = Overview.loginActivity(events(), NOW, Set.of("m1", "m2"));
            assertThat(out.logins24h()).isEqualTo(1);
            assertThat(out.logins7d()).isEqualTo(3);
            assertThat(out.failed24h()).isEqualTo(2);
            assertThat(out.failed7d()).isEqualTo(3);
            assertThat(out.people24h()).isEqualTo(1);
            assertThat(out.people7d()).isEqualTo(2);
            // Only m2 failed inside the lockout window.
            assertThat(out.lockoutCandidates()).containsExactly("m2");
            assertThat(out.mostFailures7d().get(0)).isEqualTo(new UserCount("m2", null, 2));
        }

        @Test
        void realm_wide_counts_unknown_users_too() {
            LoginActivity out = Overview.loginActivity(events(), NOW, null);
            assertThat(out.logins24h()).isEqualTo(2);
            assertThat(out.failed24h()).isEqualTo(3);
            assertThat(out.people24h()).isEqualTo(2);
        }

        @Test
        void a_login_with_an_error_is_a_failure() {
            LoginActivity out = Overview.loginActivity(List.of(new LoginEvent("LOGIN", "u", NOW - MIN, "expired_code")),
                    NOW, null);
            assertThat(out.logins7d()).isZero();
            assertThat(out.failed7d()).isEqualTo(1);
        }

        @Test
        void top_five_and_at_most_50_candidates_newest_first() {
            List<LoginEvent> evs = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                for (int k = 0; k <= i % 7; k++) {
                    evs.add(new LoginEvent("LOGIN_ERROR", "u" + i, NOW - (i + 1) * 1000L, "x"));
                }
            }
            LoginActivity out = Overview.loginActivity(evs, NOW, null);
            assertThat(out.mostFailures7d()).hasSize(5);
            assertThat(out.mostFailures7d().get(0).failures()).isEqualTo(7);
            assertThat(out.mostFailures7d().get(0).sub()).isEqualTo("u6"); // first seen with 7 wins ties
            assertThat(out.lockoutCandidates()).hasSize(50);
            assertThat(out.lockoutCandidates().get(0)).isEqualTo("u0");
        }

        @Test
        void is_locked() {
            assertThat(Overview.isLocked(new BruteForceStatus(true, 5))).isTrue();
            assertThat(Overview.isLocked(new BruteForceStatus(false, 5))).isFalse();
            assertThat(Overview.isLocked(null)).isFalse();
        }

        @Test
        void the_section_lists_who_is_locked_now_and_flags_truncation() {
            LoginActivity a = Overview.loginActivity(events(), NOW, Set.of("m1", "m2"));
            Map<String, BruteForceStatus> statuses = new HashMap<>();
            statuses.put("m2", new BruteForceStatus(true, 5));
            LoginSection s = Overview.loginSection(a, statuses, uid -> uid + "-name", 9, 4);
            assertThat(s.available()).isTrue();
            assertThat(s.lockedNow()).containsExactly(new UserCount("m2", "m2-name", 5));
            assertThat(s.mostFailures7d().get(0).username()).isEqualTo("m2-name");
            assertThat(s.truncated()).isFalse();
            assertThat(s.activeSessions()).isEqualTo(4);
            assertThat(Overview.loginSection(a, Map.of(), uid -> null, 10_000, null).truncated()).isTrue();
            assertThat(Overview.loginSection(a, Map.of(), uid -> null, 10_000, null).lockedNow()).isEmpty();
            assertThat(LoginSection.unavailable("boom", 3).activeSessions()).isEqualTo(3);
        }
    }

    @Nested
    class AccessPerApp {
        @Test
        void row() {
            Map<String, Holder> holders = Map.of(
                    "a", new Holder(true, List.of(), List.of(), false),
                    "b", new Holder(false, List.of("g"), List.of(), false),
                    "c", new Holder(true, List.of("g"), List.of(), false),
                    "d", new Holder(false, List.of("g"), List.of(), true));
            AppAccessRow row = Overview.appAccessRow("minagri-portal", "Portal", false, holders, 7);
            assertThat(row.people()).isEqualTo(3);
            assertThat(row.named()).isEqualTo(2);
            assertThat(row.viaGroup()).isEqualTo(2);
            assertThat(row.revoked()).isEqualTo(1);
            assertThat(row.activeSessions()).isEqualTo(7);
            assertThat(row.kind()).isNull();
            assertThat(row.groups()).isNull();
        }

        @Test
        void sessions_by_client() {
            assertThat(Overview.sessionsByClient(List.of(Map.entry("x", 3)))).containsExactly(Map.entry("x", 3));
            List<Map.Entry<String, Integer>> stats = new ArrayList<>();
            stats.add(new java.util.AbstractMap.SimpleEntry<>(null, 9));
            stats.add(new java.util.AbstractMap.SimpleEntry<>("y", null));
            assertThat(Overview.sessionsByClient(stats)).containsExactly(Map.entry("y", 0));
            assertThat(Overview.sessionsByClient(null)).isEmpty();
        }

        @Test
        void institution_section() {
            AppAccessRow a = Overview.appAccessRow("b-app", null, false, Map.of(), null);
            AppAccessRow b = Overview.appAccessRow("a-app", null, false,
                    Map.of("u", new Holder(true, List.of(), List.of(), false)), null);
            AppAccessRow c = Overview.appAccessRow("a-zz", null, false, Map.of(), null);
            AccessSection s = Overview.institutionAccessSection(true, null, List.of(a, b, c));
            assertThat(s.available()).isTrue();
            assertThat(s.items()).extracting(AppAccessRow::clientId).containsExactly("a-app", "a-zz", "b-app");
            AccessSection denied = Overview.institutionAccessSection(false, null, List.of());
            assertThat(denied.available()).isFalse();
            assertThat(denied.error()).isEqualTo("Your role does not include view-clients.");
            AccessSection failed = Overview.institutionAccessSection(true, "boom", List.of());
            assertThat(failed.available()).isFalse();
            assertThat(failed.error()).isEqualTo("boom");
        }

        @Test
        void platform_section() {
            AppAccessRow r1 = Overview.platformAccessRow("x", null, false, "institution", 3, List.of("Minagri"), 0, 1);
            AppAccessRow r2 = Overview.platformAccessRow("y", null, true, "cross_cutting", 9, List.of(), 1, 1);
            AppAccessRow r3 = Overview.platformAccessRow("z", null, false, "platform", 0, List.of(), 0, 5);
            AccessSection s = Overview.platformAccessSection(List.of(r1, r2, r3), List.of("q", "p"), null);
            assertThat(s.items()).extracting(AppAccessRow::clientId).containsExactly("z", "y", "x");
            assertThat(s.available()).isTrue();
            assertThat(s.error()).isEqualTo("Could not read access for: p, q");
            assertThat(r1.people()).isNull();
            assertThat(r1.viaGroup()).isNull();
            AccessSection down = Overview.platformAccessSection(List.of(), List.of("p"), "catalogue failed");
            assertThat(down.available()).isFalse();
            assertThat(down.error()).isEqualTo("catalogue failed");
        }

        @Test
        void builtins_and_exemptions_are_left_out() {
            assertThat(Overview.excludedFromPlatformAccess("realm-management", List.of())).isTrue();
            assertThat(Overview.excludedFromPlatformAccess("sso-admin-ui", List.of("sso-admin-ui"))).isTrue();
            assertThat(Overview.excludedFromPlatformAccess("minagri-portal", List.of("sso-admin-ui"))).isFalse();
            assertThat(Overview.excludedFromPlatformAccess(null, null)).isTrue();
        }

        @Test
        void session_totals() {
            Map<String, Integer> sessions = Map.of("own", 2, "shared", 10);
            assertThat(Overview.institutionSessionsTotal(sessions, List.of("own", "missing"))).isEqualTo(2);
            assertThat(Overview.institutionSessionsTotal(null, List.of("own"))).isNull();
            assertThat(Overview.realmSessionsTotal(sessions)).isEqualTo(12);
            assertThat(Overview.realmSessionsTotal(null)).isNull();
        }
    }
}
