package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;

/** Port of tests/e2e/test_employee_provisioning.py (pure half) plus extra edge cases. */
class EmployeeAccountPlannerTest {

    static final String A = "minagri";
    static final String NID = "1199780012345678";

    static ClientTarget target(String clientId, JoinColumn column, boolean configured) {
        return new ClientTarget(clientId, new JoinColumnSetting(clientId, column, configured), clientId);
    }

    static final ClientTarget NID_APP = target(A + "-tax", JoinColumn.NID, true);
    static final ClientTarget PHONE_APP = target(A + "-sms", JoinColumn.PHONE, true);
    static final ClientTarget EMAIL_APP = target(A + "-portal", JoinColumn.EMAIL, true);
    static final ClientTarget UNSET_APP = target(A + "-legacy", JoinColumn.EMAIL, false);

    static final String DEFAULT = "\u0000default";

    static EmployeeRecord employee(String number, String email, String nid, String status, String userId) {
        return new EmployeeRecord(number, DEFAULT.equals(email) ? number.toLowerCase() + "@minagri.gov.rw" : email,
                null, nid, "Jean", "Uwase", A, status, userId);
    }

    static EmployeeRecord employee(String nid) {
        return employee("EMP001", DEFAULT, nid, "active", null);
    }

    static ClientDecision denial(EmployeePlan plan, String clientId) {
        return plan.denied().stream().filter(d -> d.clientId().equals(clientId)).findFirst().orElseThrow();
    }

    static EmployeePlan plan(EmployeeRecord r, List<ClientTarget> targets, Set<String> held) {
        return EmployeeAccountPlanner.planEmployee(r, targets, true, Set.of(), held);
    }

    // ---- 1. per-client -----------------------------------------------------------------

    @Test
    void aMissingFieldDeniesThatClientOnly() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee(NID), List.of(NID_APP, PHONE_APP, EMAIL_APP));
        assertThat(p.action()).isEqualTo(PlanAction.CREATE);
        assertThat(p.granted()).containsExactlyInAnyOrder(NID_APP.clientId(), EMAIL_APP.clientId());
        assertThat(p.denied()).extracting(ClientDecision::clientId).containsExactly(PHONE_APP.clientId());
        assertThat(denial(p, PHONE_APP.clientId()).reason()).isEqualTo(DenyReason.VALUE_MISSING);
        assertThat(p.message()).isEqualTo("Jean Uwase is given 2 of 3 selected application(s).");
    }

    @Test
    void oneQualifyingClientIsEnoughForAnAccount() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee(null), List.of(NID_APP, PHONE_APP, EMAIL_APP));
        assertThat(p.action()).isEqualTo(PlanAction.CREATE);
        assertThat(p.granted()).containsExactly(EMAIL_APP.clientId());
        assertThat(p.denied()).hasSize(2);
    }

    @Test
    void noQualifyingClientMeansNoAccount() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", null, null, "active", null),
                List.of(NID_APP, PHONE_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.EMAIL_MISSING);

        p = EmployeeAccountPlanner.planEmployee(employee(null), List.of(NID_APP, PHONE_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.NO_QUALIFYING_CLIENT);
        assertThat(p.denied()).hasSize(2);
        assertThat(p.accountStatus()).isEqualTo(AccountStatus.NOT_CREATED);
        assertThat(p.username()).isEqualTo("emp001@minagri.gov.rw");
    }

    @Test
    void everyDenialNamesTheEmptyFieldAndTheColumn() {
        String m = denial(EmployeeAccountPlanner.planEmployee(employee(null), List.of(NID_APP)), NID_APP.clientId())
                .message();
        assertThat(m).contains("Jean Uwase").contains(NID_APP.clientId()).contains("NID").contains("national_id");
    }

    @Test
    void aMalformedValueIsADifferentDenialFromAnEmptyOne() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("12345"), List.of(NID_APP));
        assertThat(denial(p, NID_APP.clientId()).reason()).isEqualTo(DenyReason.VALUE_INVALID);
        assertThat(denial(p, NID_APP.clientId()).message()).contains("16 digits");
    }

    @Test
    void formatValidationCanBeRelaxedWithoutLettingEmptiesThrough() {
        EmployeePlan relaxed = EmployeeAccountPlanner.planEmployee(employee("12345"), List.of(NID_APP), false,
                Set.of(), Set.of());
        assertThat(relaxed.granted()).containsExactly(NID_APP.clientId());
        EmployeePlan stillEmpty = EmployeeAccountPlanner.planEmployee(employee(null), List.of(NID_APP), false,
                Set.of(), Set.of());
        assertThat(stillEmpty.action()).isEqualTo(PlanAction.SKIP);
    }

    // ---- 2. unconfigured client --------------------------------------------------------

    @Test
    void aClientWithNoJoinColumnDeniesEveryone() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee(null), List.of(UNSET_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.NO_QUALIFYING_CLIENT);
        assertThat(denial(p, UNSET_APP.clientId()).reason()).isEqualTo(DenyReason.COLUMN_UNSET);
        assertThat(denial(p, UNSET_APP.clientId()).message()).contains("choose one");
    }

    @Test
    void anUnconfiguredClientDoesNotBlockAConfiguredOne() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee(null), List.of(UNSET_APP, EMAIL_APP));
        assertThat(p.action()).isEqualTo(PlanAction.CREATE);
        assertThat(p.granted()).containsExactly(EMAIL_APP.clientId());
    }

    // ---- 3. nameable account ------------------------------------------------------------

    static Stream<Arguments> badEmails() {
        return Stream.of(
                Arguments.of(null, SkipReason.EMAIL_MISSING),
                Arguments.of("   ", SkipReason.EMAIL_MISSING),
                Arguments.of("jean.uwase.at.minagri", SkipReason.EMAIL_INVALID),
                Arguments.of("jean@minagri", SkipReason.EMAIL_INVALID));
    }

    @ParameterizedTest
    @MethodSource("badEmails")
    void anEmployeeWithNoUsableEmailGetsNoAccount(String email, SkipReason reason) {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", email, null, "active", null),
                List.of(EMAIL_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(reason);
        assertThat(p.username()).isNull();
        assertThat(p.decisions()).isEmpty();
    }

    @Test
    void twoEmployeesSharingAnAddressAreBothSkipped() {
        List<EmployeePlan> plans = EmployeeAccountPlanner.plan(List.of(
                employee("EMP001", "shared@minagri.gov.rw", NID, "active", null),
                employee("EMP002", "SHARED@minagri.gov.rw", NID, "active", null),
                employee("EMP003", "own@minagri.gov.rw", NID, "active", null)), List.of(NID_APP));
        assertThat(plans.get(0).reason()).isEqualTo(SkipReason.EMAIL_DUPLICATE);
        assertThat(plans.get(1).reason()).isEqualTo(SkipReason.EMAIL_DUPLICATE);
        assertThat(plans.get(1).message()).contains("more than one employee record");
        assertThat(plans.get(2).action()).isEqualTo(PlanAction.CREATE);
    }

    @Test
    void aLeaverIsNeverGivenAnAccount() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", DEFAULT, NID, "terminated", null),
                List.of(NID_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.EMPLOYMENT_ENDED);
        assertThat(p.message()).contains("No account is created for someone who has left.");
        EmployeePlan suspended = EmployeeAccountPlanner.planEmployee(
                employee("EMP001", DEFAULT, NID, "suspended", null), List.of(NID_APP));
        assertThat(suspended.action()).isEqualTo(PlanAction.CREATE);
    }

    @Test
    void anEmployeeWhoAlreadyHasAnAccountIsLinkedNotCreated() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", DEFAULT, NID, "active", "u-existing"),
                List.of(NID_APP));
        assertThat(p.action()).isEqualTo(PlanAction.LINK);
        assertThat(p.existingUserId()).isEqualTo("u-existing");
        assertThat(p.accountStatus()).isEqualTo(AccountStatus.EXISTING);
        assertThat(p.granted()).containsExactly(NID_APP.clientId());
    }

    // ---- 3b. account-aware ---------------------------------------------------------------

    @Test
    void anApplicationTheyAlreadyHoldIsSkippedNotGrantedAgain() {
        EmployeePlan p = plan(employee("EMP001", DEFAULT, NID, "active", "u-existing"), List.of(NID_APP, EMAIL_APP),
                Set.of(NID_APP.clientId()));
        assertThat(p.granted()).containsExactly(EMAIL_APP.clientId());
        assertThat(p.held()).containsExactly(NID_APP.clientId());
        assertThat(p.denied()).isEmpty();
        assertThat(p.action()).isEqualTo(PlanAction.LINK);
        assertThat(p.message()).endsWith(", and already had 1.");
        Map<String, Object> body = p.describe();
        assertThat(body.get("clients_skipped")).isEqualTo(List.of(NID_APP.clientId()));
        assertThat(body.get("clients_granted")).isEqualTo(List.of(EMAIL_APP.clientId()));
    }

    @Test
    void anExistingAccountNeedsNoEmailToBeGivenAnNidKeyedApp() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", null, NID, "active", "u-existing"),
                List.of(NID_APP));
        assertThat(p.action()).isEqualTo(PlanAction.LINK);
        assertThat(p.granted()).containsExactly(NID_APP.clientId());
        assertThat(p.accountStatus()).isEqualTo(AccountStatus.EXISTING);
        assertThat(p.username()).isNull();

        EmployeePlan noAccount = EmployeeAccountPlanner.planEmployee(employee("EMP001", null, NID, "active", null),
                List.of(NID_APP));
        assertThat(noAccount.action()).isEqualTo(PlanAction.SKIP);
        assertThat(noAccount.reason()).isEqualTo(SkipReason.EMAIL_MISSING);
        assertThat(noAccount.accountStatus()).isEqualTo(AccountStatus.NOT_CREATED);
    }

    @Test
    void anExistingAccountWithAMalformedEmailIsStillLinked() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(
                employee("EMP001", "not-an-address", NID, "active", "u-existing"), List.of(NID_APP));
        assertThat(p.action()).isEqualTo(PlanAction.LINK);
        assertThat(p.username()).isEqualTo("not-an-address");
    }

    @Test
    void anExistingAccountWithNothingToAddIsNotAFailedCreation() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(employee("EMP001", DEFAULT, null, "active", "u-existing"),
                List.of(NID_APP, PHONE_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.NOTHING_TO_ASSIGN);
        assertThat(p.accountStatus()).isEqualTo(AccountStatus.EXISTING);
        assertThat(p.message()).contains("account is unchanged").contains("2 could not identify them");
        assertThat(p.denied()).hasSize(2);
    }

    @Test
    void reRunningTheSameSelectionIsReportedAsNothingToDo() {
        EmployeePlan p = plan(employee("EMP001", DEFAULT, NID, "active", "u-existing"), List.of(NID_APP, EMAIL_APP),
                Set.of(NID_APP.clientId(), EMAIL_APP.clientId()));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.NOTHING_TO_ASSIGN);
        assertThat(p.held()).containsExactly(NID_APP.clientId(), EMAIL_APP.clientId());
        assertThat(p.granted()).isEmpty();
        assertThat(p.message()).contains("already theirs").doesNotContain("could not identify");
    }

    @Test
    void heldAndRefusedAreBothNamed() {
        EmployeePlan p = plan(employee("EMP001", DEFAULT, null, "active", "u-existing"), List.of(NID_APP, PHONE_APP),
                Set.of(NID_APP.clientId()));
        assertThat(p.message()).isEqualTo("Nothing to add for Jean Uwase: 1 application(s) already theirs and "
                + "1 could not identify them. Their account is unchanged.");
    }

    @Test
    void holdingAnAppBeatsAMissingJoinValue() {
        EmployeePlan p = plan(employee("EMP001", DEFAULT, null, "active", "u-existing"), List.of(NID_APP),
                Set.of(NID_APP.clientId()));
        assertThat(p.held()).containsExactly(NID_APP.clientId());
        assertThat(p.denied()).isEmpty();
        assertThat(p.reason()).isEqualTo(SkipReason.NOTHING_TO_ASSIGN);
    }

    @Test
    void holdingAnUnconfiguredAppIsStillHeld() {
        EmployeePlan p = plan(employee("EMP001", DEFAULT, null, "active", "u-1"), List.of(UNSET_APP),
                Set.of(UNSET_APP.clientId()));
        assertThat(p.held()).containsExactly(UNSET_APP.clientId());
    }

    @Test
    void aLeaverWithAnAccountHasNothingAddedToIt() {
        EmployeePlan p = EmployeeAccountPlanner.planEmployee(
                employee("EMP001", DEFAULT, NID, "terminated", "u-existing"), List.of(NID_APP));
        assertThat(p.action()).isEqualTo(PlanAction.SKIP);
        assertThat(p.reason()).isEqualTo(SkipReason.EMPLOYMENT_ENDED);
        assertThat(p.granted()).isEmpty();
        assertThat(p.accountStatus()).isEqualTo(AccountStatus.EXISTING);
        assertThat(p.message()).contains("left exactly as it is");
    }

    @Test
    void aSharedAddressBlocksACreationButNotAnAssignment() {
        List<EmployeePlan> plans = EmployeeAccountPlanner.plan(List.of(
                employee("EMP001", "shared@minagri.gov.rw", NID, "active", null),
                employee("EMP002", "shared@minagri.gov.rw", NID, "active", "u-existing")), List.of(NID_APP));
        assertThat(plans.get(0).reason()).isEqualTo(SkipReason.EMAIL_DUPLICATE);
        assertThat(plans.get(1).action()).isEqualTo(PlanAction.LINK);
        assertThat(plans.get(1).granted()).containsExactly(NID_APP.clientId());
    }

    // ---- 4. the report is total ----------------------------------------------------------

    @Test
    void everySelectedEmployeeComesBackInOrder() {
        List<EmployeePlan> plans = EmployeeAccountPlanner.plan(List.of(
                employee("EMP003", DEFAULT, NID, "active", null),
                employee("EMP001", null, null, "active", null),
                employee("EMP002", DEFAULT, null, "active", null)), List.of(NID_APP));
        assertThat(plans).extracting(EmployeePlan::employeeNumber).containsExactly("EMP003", "EMP001", "EMP002");
        assertThat(plans).extracting(EmployeePlan::action)
                .containsExactly(PlanAction.CREATE, PlanAction.SKIP, PlanAction.SKIP);
    }

    @Test
    void aClientThatCouldKeyNobodyIsNamedOnItsOwn() {
        List<EmployeePlan> plans = EmployeeAccountPlanner.plan(List.of(
                employee("EMP001", DEFAULT, null, "active", null), employee("EMP002", DEFAULT, null, "active", null)),
                List.of(EMAIL_APP, PHONE_APP));
        BatchTotals t = EmployeeAccountPlanner.totals(plans, List.of(EMAIL_APP, PHONE_APP));
        assertThat(t.created()).isEqualTo(2);
        assertThat(t.grants()).isEqualTo(2);
        assertThat(t.denials()).isEqualTo(2);
        assertThat(t.clientsDenyingEveryone()).containsExactly(PHONE_APP.clientId());
    }

    @Test
    void aClientEveryoneAlreadyHoldsDidNotDenyAnybody() {
        List<EmployeePlan> plans = EmployeeAccountPlanner.plan(List.of(
                        employee("EMP001", DEFAULT, NID, "active", "u-1"), employee("EMP002", DEFAULT, NID, "active", "u-2")),
                List.of(NID_APP, PHONE_APP), true,
                Map.of("EMP001", List.of(NID_APP.clientId()), "EMP002", List.of(NID_APP.clientId())));
        BatchTotals t = EmployeeAccountPlanner.totals(plans, List.of(NID_APP, PHONE_APP));
        assertThat(t.alreadyHeld()).isEqualTo(2);
        assertThat(t.grants()).isZero();
        assertThat(t.accountsExisting()).isEqualTo(2);
        assertThat(t.created()).isZero();
        assertThat(t.skipped()).isEqualTo(2);
        assertThat(t.clientsDenyingEveryone()).containsExactly(PHONE_APP.clientId());
    }

    @Test
    void anEmptyBatchNamesNoClient() {
        BatchTotals t = EmployeeAccountPlanner.totals(List.of(), List.of(NID_APP));
        assertThat(t.clientsDenyingEveryone()).isEmpty();
        assertThat(t.describe()).containsEntry("selected", 0).containsKeys("already_held", "accounts_existing",
                "clients_denying_everyone", "failed");
    }

    @Test
    void theReportShapeCarriesWhatAConsoleRenders() {
        Map<String, Object> body = EmployeeAccountPlanner.planEmployee(employee(NID), List.of(NID_APP, PHONE_APP))
                .describe();
        assertThat(body.get("clients_granted")).isEqualTo(List.of(NID_APP.clientId()));
        assertThat(body.get("clients_skipped")).isEqualTo(List.of());
        assertThat(body).containsEntry("account", "created").containsEntry("username", "emp001@minagri.gov.rw")
                .containsEntry("action", "create").containsEntry("reason", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> denied = (List<Map<String, Object>>) body.get("clients_denied");
        assertThat(denied.get(0)).containsEntry("client_id", PHONE_APP.clientId()).containsEntry("join_column", "phone")
                .containsEntry("reason", "join_value_missing");
    }

    @Test
    void aQualifiedDecisionDescribesWithoutReason() {
        ClientDecision d = new ClientDecision("c", JoinColumn.NID, true, false, null, null);
        assertThat(d.describe()).containsOnlyKeys("client_id", "join_column", "qualified");
        assertThat(d.message()).isEmpty();
    }

    // ---- records -------------------------------------------------------------------------

    @Test
    void fromRowTrimsAndDefaults() {
        EmployeeRecord r = EmployeeRecord.fromRow(Map.of("employee_number", " E1 ", "email", "  ",
                "national_id", " 1 ", "status", ""));
        assertThat(r.employeeNumber()).isEqualTo("E1");
        assertThat(r.email()).isNull();
        assertThat(r.nationalId()).isEqualTo("1");
        assertThat(r.status()).isEqualTo("active");
        assertThat(r.hasAccount()).isFalse();
        assertThat(r.displayName()).isEqualTo("E1");
        assertThat(r.valueFor(JoinColumn.NID)).isEqualTo("1");
        assertThat(r.valueFor(JoinColumn.PHONE)).isEmpty();
    }

    @Test
    void displayNameUsesWhicheverPartsExist() {
        assertThat(new EmployeeRecord("E", null, null, null, null, "Uwase", null, null, null).displayName())
                .isEqualTo("Uwase");
        assertThat(new EmployeeRecord("E", null, null, null, "Jean", null, null, null, null).displayName())
                .isEqualTo("Jean");
    }

    @Test
    void duplicateEmailsAreNormalised() {
        assertThat(EmployeeAccountPlanner.duplicateEmails(List.of(
                employee("A", "x@gov.rw", null, "active", null), employee("B", " X@GOV.RW ", null, "active", null),
                employee("C", null, null, "active", null), employee("D", "y@gov.rw", null, "active", null))))
                .containsExactly("x@gov.rw");
    }

    @Test
    void targetExposesTheEmployeeField() {
        assertThat(NID_APP.employeeField()).isEqualTo("national_id");
        assertThat(PHONE_APP.employeeField()).isEqualTo("phone");
        assertThat(UNSET_APP.configured()).isFalse();
    }
}
