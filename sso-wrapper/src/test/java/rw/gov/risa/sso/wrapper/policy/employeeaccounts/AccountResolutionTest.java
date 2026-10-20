package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.policy.employeeaccounts.HeldClients.ClientHolders;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionRequestRules.ProvisionRequestException;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;

/**
 * Batch account resolution, held-client computation, request-level rules and the report model —
 * the pure parts of employee_provisioning.py (service tests 995–1546 in the reference).
 */
class AccountResolutionTest {

    static final String A = "minagri";

    static EmployeeRecord rec(String number, String email, String userId) {
        return new EmployeeRecord(number, email, null, "1199780012345678", "Jean", number, A, "active", userId);
    }

    /** An in-memory directory recording which lookups ran. */
    static final class FakeDirectory implements AccountResolver.AccountDirectory {
        final Map<String, Account> byId = new HashMap<>();
        final Map<String, Account> byUsername = new HashMap<>();
        final Map<String, Account> byEmail = new HashMap<>();
        final Map<String, Account> byNumber = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Optional<Account> byId(String userId) {
            calls.add("id:" + userId);
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public Optional<Account> byUsername(String username) {
            calls.add("username:" + username);
            return Optional.ofNullable(byUsername.get(username));
        }

        @Override
        public Optional<Account> byEmail(String email) {
            calls.add("email:" + email);
            return Optional.ofNullable(byEmail.get(email));
        }

        @Override
        public Optional<Account> byEmployeeNumber(String employeeNumber) {
            calls.add("number:" + employeeNumber);
            return Optional.ofNullable(byNumber.get(employeeNumber));
        }
    }

    @Nested
    class Resolution {

        @Test
        void theMirrorLinkWinsAndNothingElseIsAsked() {
            FakeDirectory dir = new FakeDirectory();
            dir.byId.put("u1", new Account("u1", "jean", A));
            AccountResolver.Resolution r = AccountResolver.resolve(List.of(rec("E1", "e1@gov.rw", "u1")), A, dir);
            assertThat(r.accountOf("E1")).map(Account::id).hasValue("u1");
            assertThat(dir.calls).containsExactly("id:u1");
        }

        @Test
        void aStaleLinkFallsThroughToUsernameEmailThenEmployeeNumber() {
            FakeDirectory dir = new FakeDirectory();
            dir.byNumber.put("E1", new Account("u9", "hand-made", null));
            AccountResolver.Resolution r = AccountResolver.resolve(List.of(rec("E1", "e1@gov.rw", "gone")), A, dir);
            assertThat(dir.calls).containsExactly("id:gone", "username:e1@gov.rw", "email:e1@gov.rw", "number:E1");
            assertThat(r.accountOf("E1")).map(Account::id).hasValue("u9");
            assertThat(r.applyTo(List.of(rec("E1", "e1@gov.rw", "gone"))).get(0).keycloakUserId()).isEqualTo("u9");
        }

        @Test
        void aPreviewKnowsAboutAnAccountTheMirrorNeverRecorded() {
            FakeDirectory dir = new FakeDirectory();
            dir.byUsername.put("e1@gov.rw", new Account("u1", "e1@gov.rw", A));
            AccountResolver.Resolution r = AccountResolver.resolve(List.of(rec("E1", "e1@gov.rw", null)), A, dir);
            List<EmployeeRecord> planned = r.applyTo(List.of(rec("E1", "e1@gov.rw", null)));
            assertThat(planned.get(0).hasAccount()).isTrue();
        }

        @Test
        void nobodyResolvedClearsAStaleLink() {
            FakeDirectory dir = new FakeDirectory();
            AccountResolver.Resolution r = AccountResolver.resolve(List.of(rec("E1", "e1@gov.rw", "gone")), A, dir);
            assertThat(r.applyTo(List.of(rec("E1", "e1@gov.rw", "gone"))).get(0).keycloakUserId()).isNull();
        }

        @Test
        void aSharedIppisAddressIsNotResolvedToOneAccount() {
            FakeDirectory dir = new FakeDirectory();
            dir.byUsername.put("shared@gov.rw", new Account("u1", "shared@gov.rw", A));
            List<EmployeeRecord> recs = List.of(rec("E1", "shared@gov.rw", null), rec("E2", "SHARED@gov.rw", null));
            AccountResolver.Resolution r = AccountResolver.resolve(recs, A, dir);
            assertThat(dir.calls).noneMatch(c -> c.startsWith("username:") || c.startsWith("email:"));
            assertThat(r.resolved()).isEmpty();
            assertThat(r.refused()).isEmpty();
            List<EmployeePlan> plans = EmployeeAccountPlanner.plan(r.applyTo(recs), List.of(
                    new ClientTarget("minagri-tax", new JoinColumnSetting("minagri-tax", JoinColumn.NID, true), null)));
            assertThat(plans).allMatch(p -> p.reason() == SkipReason.EMAIL_DUPLICATE);
        }

        @Test
        void twoEmployeesResolvingToOneAccountAreBothStopped() {
            FakeDirectory dir = new FakeDirectory();
            dir.byId.put("u1", new Account("u1", "jean", A));
            List<EmployeeRecord> recs = List.of(rec("E1", "a@gov.rw", "u1"), rec("E2", "b@gov.rw", "u1"),
                    rec("E3", "c@gov.rw", null));
            AccountResolver.Resolution r = AccountResolver.resolve(recs, A, dir);
            assertThat(r.refused()).containsOnlyKeys("E1", "E2");
            assertThat(r.refused().get("E1").reason()).isEqualTo(SkipReason.ACCOUNT_AMBIGUOUS);
            assertThat(r.refused().get("E1").message()).contains("same SSO account as E2");
            assertThat(r.refused().get("E2").message()).contains("same SSO account as E1");
            assertThat(r.resolved()).doesNotContainKeys("E1", "E2");
            assertThat(r.applyTo(recs)).extracting(EmployeeRecord::employeeNumber).containsExactly("E3");
            assertThat(r.refusalPlans()).extracting(EmployeePlan::reason)
                    .containsExactly(SkipReason.ACCOUNT_AMBIGUOUS, SkipReason.ACCOUNT_AMBIGUOUS);
        }

        @Test
        void anAccountBelongingToAnotherInstitutionIsRefused() {
            FakeDirectory dir = new FakeDirectory();
            dir.byId.put("u1", Account.of("u1", "jean", Map.of("institution", List.of(" MINISANTE "))));
            AccountResolver.Resolution r = AccountResolver.resolve(List.of(rec("E1", "e@gov.rw", "u1")), "MinAgri", dir);
            assertThat(r.refused().get("E1").reason()).isEqualTo(SkipReason.ACCOUNT_OTHER_INSTITUTION);
            assertThat(r.refused().get("E1").message()).contains("another institution (minisante)");
            assertThat(r.accountOf("E1")).isEmpty();
            EmployeePlan p = r.refusalPlans().get(0);
            assertThat(p.action()).isEqualTo(PlanAction.SKIP);
            assertThat(p.displayName()).isEqualTo("E1");
            assertThat(p.accountStatus()).isEqualTo(AccountStatus.NOT_CREATED);
        }

        @Test
        void anAccountWithNoInstitutionRecordedIsNotTreatedAsAnothers() {
            FakeDirectory dir = new FakeDirectory();
            dir.byId.put("u1", Account.of("u1", "jean", Map.of()));
            dir.byId.put("u2", Account.of("u2", "j2", Map.of("institution", List.of("MINAGRI"))));
            AccountResolver.Resolution r = AccountResolver.resolve(
                    List.of(rec("E1", "a@gov.rw", "u1"), rec("E2", "b@gov.rw", "u2")), A, dir);
            assertThat(r.refused()).isEmpty();
            assertThat(r.resolved()).containsOnlyKeys("E1", "E2");
        }

        @Test
        void anOtherInstitutionAccountDoesNotAlsoCountAsAmbiguous() {
            FakeDirectory dir = new FakeDirectory();
            dir.byId.put("u1", new Account("u1", "x", "rdb"));
            AccountResolver.Resolution r = AccountResolver.resolve(
                    List.of(rec("E1", "a@gov.rw", "u1"), rec("E2", "b@gov.rw", "u1")), A, dir);
            assertThat(r.refused().values()).allMatch(x -> x.reason() == SkipReason.ACCOUNT_OTHER_INSTITUTION);
        }

        @Test
        void institutionOfNormalises() {
            assertThat(Account.institutionOf(null)).isNull();
            assertThat(Account.institutionOf(Map.of("institution", List.of("  ")))).isNull();
            assertThat(Account.institutionOf(Map.of("institution", List.of()))).isNull();
            assertThat(Account.institutionOf(Map.of("institution", List.of("RDB")))).isEqualTo("rdb");
        }
    }

    @Nested
    class Held {

        static final ClientTarget APP = new ClientTarget("minagri-portal",
                new JoinColumnSetting("minagri-portal", JoinColumn.EMAIL, true), null);
        static final ClientTarget APP2 = new ClientTarget("minagri-tax",
                new JoinColumnSetting("minagri-tax", JoinColumn.NID, true), null);

        @Test
        void aNamedAssignmentIsHeld() {
            Map<String, Set<String>> held = HeldClients.compute(List.of(APP), Map.of("E1", "u1"),
                    Map.of(APP.clientId(), new ClientHolders(Set.of("u1"), Set.of(), Set.of())), u -> Set.of());
            assertThat(held.get("E1")).containsExactly(APP.clientId());
        }

        @Test
        void anApplicationTheInstitutionsGroupAlreadyHoldsIsHeld() {
            List<String> lookups = new ArrayList<>();
            Map<String, Set<String>> held = HeldClients.compute(List.of(APP, APP2), Map.of("E1", "u1"),
                    Map.of(APP.clientId(), new ClientHolders(Set.of(), Set.of("g-minagri"), Set.of()),
                            APP2.clientId(), new ClientHolders(Set.of(), Set.of("g-minagri"), Set.of())),
                    u -> {
                        lookups.add(u);
                        return Set.of("g-minagri");
                    });
            assertThat(held.get("E1")).containsExactly(APP.clientId(), APP2.clientId());
            assertThat(lookups).containsExactly("u1");
        }

        @Test
        void aRevokedUserIsNotAHolder() {
            Map<String, Set<String>> held = HeldClients.compute(List.of(APP), Map.of("E1", "u1"),
                    Map.of(APP.clientId(), new ClientHolders(Set.of("u1"), Set.of("g"), Set.of("u1"))),
                    u -> Set.of("g"));
            assertThat(held.get("E1")).isEmpty();
        }

        @Test
        void aPolicyReadFailureGrantsRatherThanClaimingAlreadyHeld() {
            Map<String, Set<String>> held = HeldClients.compute(List.of(APP), Map.of("E1", "u1"), Map.of(),
                    u -> Set.of());
            assertThat(held).containsEntry("E1", Set.of());
        }

        @Test
        void groupsAreOnlyReadWhenAClientAdmitsOne() {
            List<String> lookups = new ArrayList<>();
            HeldClients.compute(List.of(APP), Map.of("E1", "u1"),
                    Map.of(APP.clientId(), new ClientHolders(Set.of(), Set.of(), Set.of())), u -> {
                        lookups.add(u);
                        return Set.of();
                    });
            assertThat(lookups).isEmpty();
        }

        @Test
        void employeesWithoutAnAccountHoldNothing() {
            Map<String, String> ids = new HashMap<>();
            ids.put("E2", null);
            assertThat(HeldClients.compute(List.of(APP), ids, Map.of(), u -> Set.of())).isEmpty();
        }

        @Test
        void aNullGroupLookupIsEmpty() {
            Map<String, Set<String>> held = HeldClients.compute(List.of(APP), Map.of("E1", "u1"),
                    Map.of(APP.clientId(), new ClientHolders(null, Set.of("g"), null)), u -> null);
            assertThat(held.get("E1")).isEmpty();
        }
    }

    @Nested
    class Request {

        @Test
        void selectingNoClientIsRefusedRatherThanSkippingEveryone() {
            assertThatThrownBy(() -> ProvisionRequestRules.clientIds(List.of(" ", "")))
                    .isInstanceOf(ProvisionRequestException.class).hasMessageContaining("at least one application");
            assertThatThrownBy(() -> ProvisionRequestRules.clientIds(null)).isInstanceOf(ProvisionRequestException.class);
            assertThat(ProvisionRequestRules.clientIds(List.of(" a ", "b", "a"))).containsExactly("a", "b");
        }

        @Test
        void aBatchLargerThanTheCapIsRefusedBeforeAnythingIsCreated() {
            assertThatThrownBy(() -> ProvisionRequestRules.employeeNumbers(List.of("E1", "E2", "E3"), 2))
                    .isInstanceOf(ProvisionRequestException.class).hasMessageContaining("at most 2")
                    .satisfies(e -> assertThat(((ProvisionRequestException) e).status()).isEqualTo(400));
            assertThat(ProvisionRequestRules.employeeNumbers(List.of("E1", " E1", "E2"), 2)).containsExactly("E1", "E2");
            assertThatThrownBy(() -> ProvisionRequestRules.employeeNumbers(List.of(), 2))
                    .hasMessage("Select at least one employee.");
        }

        @Test
        void anEmployeeOfAnotherInstitutionIsNotFound() {
            ProvisionRequestRules.Found f = ProvisionRequestRules.order(List.of("E3", "OTHER", "E1"),
                    List.of(rec("E1", "a@gov.rw", null), rec("E3", "c@gov.rw", null)));
            assertThat(f.records()).extracting(EmployeeRecord::employeeNumber).containsExactly("E3", "E1");
            assertThat(f.missing()).containsExactly("OTHER");
            EmployeePlan p = ProvisionRequestRules.notFound("OTHER", A);
            assertThat(p.reason()).isEqualTo(SkipReason.NOT_FOUND);
            assertThat(p.message()).isEqualTo("No employee 'OTHER' in minagri's employee list. Either the selection is "
                    + "stale, or IPPIS no longer reports them here.");
        }

        @Test
        void rowsGoBackIntoTheAdminsOrder() {
            List<String> sorted = ProvisionRequestRules.inRequestOrder(List.of("E2", "X", "E1", "E3"), s -> s,
                    List.of("E3", "E1", "E2"));
            assertThat(sorted).containsExactly("E3", "E1", "E2", "X");
        }
    }

    @Nested
    class Report {

        static final ClientTarget APP = new ClientTarget("minagri-tax",
                new JoinColumnSetting("minagri-tax", JoinColumn.NID, true), "Tax");

        @Test
        void aDryRunReportsTheSameRefusalsAndThePlannedOutcome() {
            EmployeePlan create = EmployeeAccountPlanner.planEmployee(rec("E1", "e1@gov.rw", null), List.of(APP));
            ReportRow row = ReportRow.preview(create, null);
            assertThat(row.outcome()).isEqualTo(Outcome.CREATED);
            assertThat(row.describe()).containsEntry("outcome", "created").containsEntry("account", "created")
                    .doesNotContainKey("user_id");
            EmployeePlan skip = ProvisionRequestRules.notFound("X", A);
            assertThat(ReportRow.preview(skip, null).outcome()).isEqualTo(Outcome.SKIPPED);
        }

        @Test
        void anExistingAccountIsReportedUnderItsOwnUsername() {
            EmployeePlan link = EmployeeAccountPlanner.planEmployee(rec("E1", "e1@gov.rw", "u1"), List.of(APP));
            ReportRow row = ReportRow.preview(link, new Account("u1", "hand.made", A));
            assertThat(row.username()).isEqualTo("hand.made");
            assertThat(row.outcome()).isEqualTo(Outcome.LINKED);
            assertThat(row.account()).isEqualTo(AccountStatus.EXISTING);
        }

        @Test
        void aFailedCreationHasNoAccount() {
            EmployeePlan create = EmployeeAccountPlanner.planEmployee(rec("E1", "e1@gov.rw", null), List.of(APP));
            ReportRow failed = ReportRow.of(create, Outcome.SKIPPED, null).failed("boom", false);
            assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(failed.account()).isEqualTo(AccountStatus.NOT_CREATED);
            assertThat(failed.describe()).containsEntry("reason", "error").containsEntry("message", "boom");
            assertThat(ReportRow.of(create, Outcome.SKIPPED, null).failed("x", true).account())
                    .isEqualTo(AccountStatus.EXISTING);
        }

        @Test
        void totalsAreCountedFromRowsWhereTheyDiffer() {
            EmployeePlan a = EmployeeAccountPlanner.planEmployee(rec("E1", "e1@gov.rw", null), List.of(APP));
            EmployeePlan b = EmployeeAccountPlanner.planEmployee(rec("E2", "e2@gov.rw", null), List.of(APP));
            ReportRow ok = ReportRow.of(a, Outcome.SKIPPED, null).applied(true, "new-1", List.of(APP.clientId()),
                    Map.of("email", "sent"));
            ReportRow bad = ReportRow.of(b, Outcome.SKIPPED, null).failed("boom", false);
            BatchTotals t = ProvisionReport.summarise(List.of(a, b), List.of(APP), List.of(ok, bad));
            assertThat(t.created()).isEqualTo(1);
            assertThat(t.failed()).isEqualTo(1);
            // Faithful to the reference: a failed row keeps the plan's clients_granted (the
            // reference only rewrites outcome/reason/message on failure), so it still counts.
            assertThat(t.grants()).isEqualTo(2);
            assertThat(t.selected()).isEqualTo(2);
            assertThat(ok.describe()).containsEntry("user_id", "new-1").containsEntry("email", "sent");

            ProvisionReport report = new ProvisionReport(A, false, List.of(APP), t, List.of(ok, bad));
            Map<String, Object> body = report.describe();
            assertThat(body).containsEntry("institution", A).containsEntry("dry_run", false)
                    .containsKeys("clients", "totals", "employees");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clients = (List<Map<String, Object>>) body.get("clients");
            assertThat(clients.get(0)).containsEntry("client_id", "minagri-tax").containsEntry("join_column", "nid")
                    .containsEntry("employee_field", "national_id").containsEntry("configured", true)
                    .containsEntry("name", "Tax");
        }

        @Test
        void aLinkedRowIsLinked() {
            EmployeePlan link = EmployeeAccountPlanner.planEmployee(rec("E1", "e1@gov.rw", "u1"), List.of(APP));
            ReportRow row = ReportRow.of(link, Outcome.SKIPPED, null).applied(false, "u1", List.of(), null);
            assertThat(row.outcome()).isEqualTo(Outcome.LINKED);
            assertThat(row.clientsGranted()).isEmpty();
        }
    }
}
