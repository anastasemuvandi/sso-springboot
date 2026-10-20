package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BulkPlannerTest {

    static final String PORTAL = "minagri-portal";
    static final String REPORTS = "minagri-reports";
    static final String SHARED = "irembo";

    final Map<String, Column> columns = new LinkedHashMap<>();
    final Map<String, RosterEmployee> roster = new LinkedHashMap<>();
    final Map<String, AccountMatch> accounts = new LinkedHashMap<>();
    final Map<String, Map<String, Holder>> holders = new LinkedHashMap<>();

    BulkPlannerTest() {
        columns.put(PORTAL, AccessMatrix.columnFor(PORTAL, "Portal", false, true));
        columns.put(REPORTS, AccessMatrix.columnFor(REPORTS, "Reports", false, true));
        columns.put(SHARED, AccessMatrix.columnFor(SHARED, "Irembo", true, false));
        for (String[] e : new String[][] {
            {"E001", "active"}, {"E002", "active"}, {"E003", "active"}, {"E004", "active"},
            {"E005", "terminated"}, {"E006", "active"}}) {
            roster.put(e[0], new RosterEmployee(e[0], "F" + e[0], "L", null, null, e[1], null, null, null));
        }
        accounts.put("E001", AccountMatch.existing("u1"));
        accounts.put("E002", AccountMatch.existing("u2"));
        accounts.put("E003", AccountMatch.existing("u3"));
        accounts.put("E004", AccountMatch.NONE);
        accounts.put("E005", AccountMatch.existing("u5"));
        accounts.put("E006", AccountMatch.ELSEWHERE);
        Holder named = new Holder(true, List.of(), List.of(), false);
        Holder denied = new Holder(false, List.of("g"), List.of("Minagri"), true);
        holders.put(PORTAL, Map.of("u1", named, "u2", denied));
        holders.put(REPORTS, Map.of("u3", named));
        holders.put(SHARED, Map.of());
    }

    BulkPlan.Ready ready(BulkPlan p) {
        assertThat(p).isInstanceOf(BulkPlan.Ready.class);
        return (BulkPlan.Ready) p;
    }

    @Test
    void request_checks() {
        assertThat(BulkPlanner.validateRequest("delete", "ui", 1)).contains(BulkRequestRefusal.INVALID_ACTION);
        assertThat(BulkPlanner.validateRequest("grant", "email", 1)).contains(BulkRequestRefusal.INVALID_CHANNEL);
        assertThat(BulkPlanner.validateRequest("grant", "auto_provisioning", 1)).isEmpty();
        assertThat(BulkPlanner.validateRequest("grant", "bulk", 0)).contains(BulkRequestRefusal.NO_ITEMS);
        assertThat(BulkPlanner.validateRequest("revoke", "ui", 2001)).contains(BulkRequestRefusal.TOO_MANY_ITEMS);
        assertThat(BulkPlanner.validateRequest("revoke", "ui", 2000)).isEmpty();
        assertThat(BulkRequestRefusal.TOO_MANY_ITEMS.message()).contains("2000");
    }

    @Test
    void any_out_of_institution_employee_refuses_the_whole_request() {
        BulkPlan p = BulkPlanner.plan(Action.GRANT,
                List.of(new BulkItem("E003", PORTAL), new BulkItem("E100", PORTAL), new BulkItem("E099", PORTAL)),
                columns, roster, accounts, holders, true);
        assertThat(p).isEqualTo(new BulkPlan.Forbidden(BulkRequestRefusal.EMPLOYEE_OUTSIDE_INSTITUTION,
                List.of("E099", "E100")));
        assertThat(BulkRequestRefusal.EMPLOYEE_OUTSIDE_INSTITUTION.httpStatus()).isEqualTo(403);
    }

    @Test
    void a_client_outside_scope_refuses_the_whole_request() {
        BulkPlan p = BulkPlanner.plan(Action.GRANT, List.of(new BulkItem("E003", "minisante-hmis")),
                columns, roster, accounts, holders, true);
        assertThat(p).isEqualTo(new BulkPlan.Forbidden(BulkRequestRefusal.CLIENT_OUTSIDE_SCOPE,
                List.of("minisante-hmis")));
    }

    @Test
    void grants_are_grouped_per_client_and_rejections_reported_per_item() {
        BulkPlan.Ready r = ready(BulkPlanner.plan(Action.GRANT, List.of(
                new BulkItem("E003", PORTAL),   // has account, no access: write
                new BulkItem("E001", PORTAL),   // already has: state_changed
                new BulkItem("E002", PORTAL),   // denied: has no access -> write (lifts denial)
                new BulkItem("E004", PORTAL),   // no account -> provision
                new BulkItem("E004", REPORTS),
                new BulkItem("E005", PORTAL),   // leaver
                new BulkItem("E006", PORTAL),   // elsewhere
                new BulkItem("E003", SHARED),   // read-only column
                new BulkItem("E003", PORTAL)),  // duplicate
                columns, roster, accounts, holders, true));
        assertThat(r.writes()).containsOnlyKeys(PORTAL);
        assertThat(r.writes().get(PORTAL)).containsExactly(new PlannedWrite("u3", "E003"), new PlannedWrite("u2", "E002"));
        assertThat(r.toProvision()).containsExactly(Map.entry("E004", List.of(PORTAL, REPORTS)));
        assertThat(r.rejected()).extracting(ItemResult::employeeNumber, ItemResult::clientId, ItemResult::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("E003", PORTAL, "duplicate"),
                        org.assertj.core.groups.Tuple.tuple("E001", PORTAL, "state_changed"),
                        org.assertj.core.groups.Tuple.tuple("E005", PORTAL, "employee_left"),
                        org.assertj.core.groups.Tuple.tuple("E006", PORTAL, "account_unresolved"),
                        org.assertj.core.groups.Tuple.tuple("E003", SHARED, "not_manageable"));
        assertThat(r.rejected()).allSatisfy(i -> assertThat(i.status()).isEqualTo(ItemStatus.REJECTED));
        assertThat(r.rejected().get(0).sub()).isEqualTo("u3");
        assertThat(r.rejected().get(1).message()).startsWith("This person already has access");
    }

    @Test
    void a_tier_that_may_not_create_accounts_is_told_so_per_item() {
        BulkPlan.Ready r = ready(BulkPlanner.plan(Action.GRANT,
                List.of(new BulkItem("E004", PORTAL), new BulkItem("E003", PORTAL)),
                columns, roster, accounts, holders, false));
        assertThat(r.toProvision()).isEmpty();
        assertThat(r.rejected()).singleElement().satisfies(i -> {
            assertThat(i.reason()).isEqualTo("cannot_create_account");
            assertThat(i.sub()).isNull();
        });
        assertThat(r.writes().get(PORTAL)).containsExactly(new PlannedWrite("u3", "E003"));
    }

    @Test
    void revoke_mode_cannot_grant_and_no_account_is_state_changed() {
        BulkPlan.Ready r = ready(BulkPlanner.plan(Action.REVOKE, List.of(
                new BulkItem("E003", PORTAL), new BulkItem("E004", PORTAL), new BulkItem("E001", PORTAL),
                new BulkItem("E002", PORTAL), new BulkItem("E005", REPORTS)),
                columns, roster, accounts, holders, true));
        assertThat(r.writes().get(PORTAL)).containsExactly(new PlannedWrite("u1", "E001"));
        assertThat(r.rejected()).extracting(ItemResult::employeeNumber, ItemResult::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple("E003", "state_changed"),
                org.assertj.core.groups.Tuple.tuple("E004", "state_changed"),
                org.assertj.core.groups.Tuple.tuple("E002", "state_changed"),  // denied = no access
                org.assertj.core.groups.Tuple.tuple("E005", "state_changed"));
        assertThat(r.rejected().get(0).message()).startsWith("This person no longer has access");
    }

    @Test
    void a_leaver_may_be_revoked() {
        holders.put(REPORTS, Map.of("u5", new Holder(true, List.of(), List.of(), false)));
        BulkPlan.Ready r = ready(BulkPlanner.plan(Action.REVOKE, List.of(new BulkItem("E005", REPORTS)),
                columns, roster, accounts, holders, true));
        assertThat(r.writes().get(REPORTS)).containsExactly(new PlannedWrite("u5", "E005"));
    }

    @Test
    void settle_reports_state_changed_for_what_the_write_did_not_do() {
        List<PlannedWrite> todo = List.of(new PlannedWrite("u3", "E003"), new PlannedWrite("u2", "E002"));
        List<ItemResult> out = BulkPlanner.settle(Action.GRANT, PORTAL, todo, Set.of("u3"));
        assertThat(out).extracting(ItemResult::status).containsExactly(ItemStatus.APPLIED, ItemStatus.REJECTED);
        assertThat(out.get(1).reason()).isEqualTo("state_changed");
        assertThat(out.get(0).reason()).isNull();
    }

    @Test
    void a_failed_write_fails_every_item_of_that_client() {
        List<ItemResult> out = BulkPlanner.failed(PORTAL, List.of(new PlannedWrite("u3", "E003")), "boom");
        assertThat(out).singleElement().satisfies(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.FAILED);
            assertThat(i.reason()).isEqualTo("keycloak_error");
            assertThat(i.message()).isEqualTo("boom");
        });
    }
}
