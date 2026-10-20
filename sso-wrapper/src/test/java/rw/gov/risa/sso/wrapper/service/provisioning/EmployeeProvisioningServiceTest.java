package rw.gov.risa.sso.wrapper.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionRefusedException;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyProperties;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;

/** Spec §5.11 rule by rule, against mocked Keycloak / mirror / mail. */
@ExtendWith(OutputCaptureExtension.class)
class EmployeeProvisioningServiceTest {

    ProvisioningKeycloak kc = mock(ProvisioningKeycloak.class);
    EmployeeMirror mirror = mock(EmployeeMirror.class);
    ClientAccessService access = mock(ClientAccessService.class);
    ClientScopeService scopes = mock(ClientScopeService.class);
    JoinKeyService joinKeys = mock(JoinKeyService.class);
    SetupLinkService setup = mock(SetupLinkService.class);
    Mailer mailer = mock(Mailer.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    KeycloakExec sa = mock(KeycloakExec.class);
    List<ClientRepresentation> catalogue = new ArrayList<>();

    static final String URL = "http://wrapper.test/account/setup?token=SETUP-TOKEN";

    EmployeeProvisioningService service(boolean debug, String devPassword) {
        return new EmployeeProvisioningService(kc, mirror, access, scopes, joinKeys,
                new JoinKeyProperties(true, false, true, 200), setup, mailer, audit,
                new ProvisioningProperties(5, devPassword), ProvisioningFixtures.sso(debug));
    }

    EmployeeProvisioningService service() {
        return service(false, "");
    }

    @BeforeEach
    void setUp() {
        catalogue.add(ProvisioningFixtures.client("minagri-email-app", "email"));
        catalogue.add(ProvisioningFixtures.client("minagri-nid-app", "nid"));
        catalogue.add(ProvisioningFixtures.client("minagri-unset-app", null));
        catalogue.add(ProvisioningFixtures.client("minisante-hmis", "email"));
        when(kc.catalogue()).thenReturn(catalogue);
        when(scopes.scopeOf(any())).thenAnswer(i -> {
            ClientRepresentation c = i.getArgument(0);
            return ClientScope.forInstitution(c.getClientId(),
                    c.getClientId().startsWith("minagri-") ? "minagri" : "minisante");
        });
        when(access.serviceAccount()).thenReturn(sa);
        when(access.assignment(any(), anyString())).thenReturn(ClientAssignment.EMPTY);
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendWelcome(any())).thenReturn(DeliveryResult.sent());
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
        when(setup.issueUrl(anyString(), anyCollection())).thenReturn(URL);
        when(kc.createUser(any())).thenAnswer(i -> "new-" + ((UserRepresentation) i.getArgument(0)).getUsername());
        when(kc.groupIdByPath("/minagri")).thenReturn(Optional.of("gid-minagri"));
    }

    void mirrorHas(EmployeeRecord... records) {
        when(mirror.records(eq("minagri"), anyList())).thenReturn(List.of(records));
    }

    static ReportRow row(ProvisionReport r, String number) {
        return r.employees().stream().filter(x -> x.employeeNumber().equals(number)).findFirst().orElseThrow();
    }

    List<AuditEvent> audited() {
        ArgumentCaptor<AuditEvent.Builder> c = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(audit, org.mockito.Mockito.atLeast(0)).emit(c.capture());
        return c.getAllValues().stream().map(AuditEvent.Builder::build).toList();
    }

    // ---- creating an account: the temporary password is never disclosed ----------------------

    @Test
    void createdAccountsTemporaryPasswordNeverAppearsInResponseLogsEmailOrAudit(CapturedOutput output)
            throws Exception {
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of("E1001"), List.of("minagri-email-app"), false, "1.2.3.4"));

        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(kc).setPassword(eq("new-alice@minagri.gov.rw"), password.capture(), eq(true));
        String secret = password.getValue();
        assertThat(secret).hasSizeGreaterThan(20);

        // the account: username = email, UPDATE_PASSWORD, institution + employee number attributes
        ArgumentCaptor<UserRepresentation> rep = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(kc).createUser(rep.capture());
        assertThat(rep.getValue().getUsername()).isEqualTo("alice@minagri.gov.rw");
        assertThat(rep.getValue().getRequiredActions()).containsExactly("UPDATE_PASSWORD");
        assertThat(rep.getValue().getCredentials()).isNullOrEmpty();
        assertThat(rep.getValue().getAttributes()).containsEntry("institution", List.of("minagri"))
                .containsEntry("employee_number", List.of("E1001"));

        // response
        String json = new ObjectMapper().writeValueAsString(ProvisionViews.of(report));
        assertThat(json).doesNotContain(secret).contains("\"password\":\"set_by_holder\"");
        // logs
        assertThat(output.getAll()).doesNotContain(secret);
        // email: the record has no password field; render the real body and look
        ArgumentCaptor<Mailer.WelcomeMail> mail = ArgumentCaptor.forClass(Mailer.WelcomeMail.class);
        verify(mailer).sendWelcome(mail.capture());
        assertThat(mail.getValue().setPasswordUrl()).isEqualTo(URL);
        Mailer real = new Mailer(mock(ObjectProvider.class), new MailSettings("from@gov.rw", "http://login", true), "",
                mock(MailDeliveryLog.class));
        String body = real.renderWelcome(mail.getValue());
        assertThat(body).doesNotContain(secret).contains(URL).contains("MINAGRI-EMAIL-APP");
        assertThat(mail.getValue().toString()).doesNotContain(secret);
        // audit
        assertThat(audited().toString()).doesNotContain(secret);

        ReportRow r = row(report, "E1001");
        assertThat(r.account().code()).isEqualTo("created");
        assertThat(r.clientsGranted()).containsExactly("minagri-email-app");
        assertThat(r.extras()).containsEntry("welcome_email", "sent").containsEntry("password_email", "sent");
        // per user, never via the group; the NEW account joins the institution group (membership only)
        verify(access).admitUser(sa, "minagri-email-app", "new-alice@minagri.gov.rw");
        verify(access, never()).grantGroup(any(), anyString(), anyString());
        verify(kc).addToGroup("new-alice@minagri.gov.rw", "gid-minagri");
        verify(setup).issueUrl("new-alice@minagri.gov.rw", List.of("minagri-email-app"));
        verify(mirror).link("E1001", "new-alice@minagri.gov.rw");
    }

    @Test
    void eachTemporaryPasswordIsFreshAndSatisfiesThePolicy() {
        String a = TemporaryPasswords.generate();
        String b = TemporaryPasswords.generate();
        assertThat(a).isNotEqualTo(b).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*").hasSizeGreaterThan(40);
    }

    // ---- per (employee, client) validation -------------------------------------------------

    @Test
    void validationIsPerEmployeeAndClientNeverAllOrNothing() {
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null),
                ProvisioningFixtures.employee("E1002", "bob@minagri.gov.rw", "1198780012345678", null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001", "E1002"), List.of("minagri-email-app", "minagri-nid-app", "minagri-unset-app"), false,
                null));

        ReportRow alice = row(report, "E1001");
        assertThat(alice.clientsGranted()).containsExactly("minagri-email-app");
        assertThat(alice.clientsDenied()).extracting(d -> d.clientId() + ":" + d.reason().code())
                .containsExactly("minagri-nid-app:join_value_missing", "minagri-unset-app:join_column_unset");
        ReportRow bob = row(report, "E1002");
        assertThat(bob.clientsGranted()).containsExactly("minagri-email-app", "minagri-nid-app");
        assertThat(bob.clientsDenied()).extracting(d -> d.reason().code()).containsExactly("join_column_unset");
        assertThat(report.totals().clientsDenyingEveryone()).containsExactly("minagri-unset-app");
        // one audit event per (user, client) assignment
        List<AuditEvent> grants = audited().stream().filter(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED)
                .toList();
        assertThat(grants).hasSize(3);
        assertThat(grants).allSatisfy(e -> {
            assertThat(e.extra()).containsKeys("target_user", "target_client", "batch_id", "channel");
            assertThat(e.extra().get("channel")).isEqualTo("auto_provisioning");
        });
    }

    @Test
    void noQualifyingClientMeansNoAccountButNoEmailIsACreationOnlyRule() {
        UserRepresentation existing = ProvisioningFixtures.user("u-carol", "carol", "minagri");
        when(kc.userByEmployeeNumber("E1004")).thenReturn(Optional.of(existing));
        mirrorHas(ProvisioningFixtures.employee("E1003", null, null, null),
                ProvisioningFixtures.employee("E1004", null, "1198780012345678", null),
                ProvisioningFixtures.employee("E1005", "dan@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1003", "E1004", "E1005"), List.of("minagri-nid-app"), false, null));

        assertThat(row(report, "E1003").reason()).isEqualTo("email_missing");
        assertThat(row(report, "E1003").account().code()).isEqualTo("not_created");
        // existing account, no email in IPPIS: still granted (email is the creation-only rule)
        assertThat(row(report, "E1004").clientsGranted()).containsExactly("minagri-nid-app");
        assertThat(row(report, "E1004").account().code()).isEqualTo("existing");
        // no NID → the only client denies → no account is created
        assertThat(row(report, "E1005").reason()).isEqualTo("no_qualifying_client");
        verify(kc, never()).createUser(any());
    }

    // ---- held apps: deny beats allow -------------------------------------------------------

    @Test
    void appsAlreadyHeldAreSkippedAndADeniedUserIsNotAHolder() {
        UserRepresentation u = ProvisioningFixtures.user("u-alice", "alice@minagri.gov.rw", "minagri");
        when(kc.userById("u-alice")).thenReturn(Optional.of(u));
        when(access.assignment(sa, "minagri-email-app"))
                .thenReturn(new ClientAssignment(List.of("u-alice"), List.of(), List.of()));
        when(access.assignment(sa, "minagri-nid-app"))
                .thenReturn(new ClientAssignment(List.of("u-alice"), List.of(), List.of("u-alice")));
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", "1198780012345678", "u-alice"));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app", "minagri-nid-app"), false, null));

        ReportRow r = row(report, "E1001");
        assertThat(r.clientsSkipped()).containsExactly("minagri-email-app");
        assertThat(r.clientsGranted()).containsExactly("minagri-nid-app");
        verify(access, never()).admitUser(any(), eq("minagri-email-app"), anyString());
        verify(access).admitUser(sa, "minagri-nid-app", "u-alice");
    }

    @Test
    void heldThroughAnAdmittedGroupCountsAsHeld() {
        UserRepresentation u = ProvisioningFixtures.user("u-alice", "alice@minagri.gov.rw", "minagri");
        when(kc.userById("u-alice")).thenReturn(Optional.of(u));
        when(kc.directGroupIds("u-alice")).thenReturn(java.util.Set.of("gid-minagri"));
        when(access.assignment(sa, "minagri-email-app"))
                .thenReturn(new ClientAssignment(List.of(), List.of("gid-minagri"), List.of()));
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, "u-alice"));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app"), false, null));

        assertThat(row(report, "E1001").reason()).isEqualTo("nothing_to_assign");
        verify(access, never()).admitUser(any(), anyString(), anyString());
        verify(mailer, never()).sendAccessGranted(any());
    }

    // ---- an existing account is never recreated or altered ---------------------------------

    @Test
    void existingAccountIsOnlyGrantedAndToldNeverAltered() {
        UserRepresentation u = ProvisioningFixtures.user("u-alice", "alice.legacy", "minagri");
        when(kc.userByUsername("alice@minagri.gov.rw")).thenReturn(Optional.of(u));
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app"), false, null));

        ReportRow r = row(report, "E1001");
        assertThat(r.account().code()).isEqualTo("existing");
        assertThat(r.username()).isEqualTo("alice.legacy");
        assertThat(r.extras()).containsEntry("password_email", "not_needed").containsEntry("welcome_email", "sent");
        verify(kc, never()).createUser(any());
        verify(kc, never()).setPassword(anyString(), anyString(), anyBoolean());
        verify(kc, never()).addToGroup(anyString(), anyString());
        verify(mailer, never()).sendWelcome(any());
        ArgumentCaptor<Mailer.AccessChangeMail> m = ArgumentCaptor.forClass(Mailer.AccessChangeMail.class);
        verify(mailer).sendAccessGranted(m.capture());
        assertThat(m.getValue().to()).isEqualTo(u.getEmail());
        assertThat(m.getValue().applications()).containsExactly("MINAGRI-EMAIL-APP (minagri-email-app)");
        verify(access).admitUser(sa, "minagri-email-app", "u-alice");
        verify(mirror).link("E1001", "u-alice");
    }

    // ---- whole-batch account resolution ----------------------------------------------------

    @Test
    void accountsAreResolvedForTheWholeBatchFirst() {
        // E1001 and E1002 both carry the same employee-number match → ambiguous for both
        UserRepresentation shared = ProvisioningFixtures.user("u-shared", "shared", "minagri");
        when(kc.userByEmployeeNumber("E1001")).thenReturn(Optional.of(shared));
        when(kc.userByEmployeeNumber("E1002")).thenReturn(Optional.of(shared));
        // E1003 resolves to an account of ANOTHER institution
        when(kc.userByUsername("eve@minagri.gov.rw"))
                .thenReturn(Optional.of(ProvisioningFixtures.user("u-eve", "eve@minagri.gov.rw", "MINISANTE")));
        mirrorHas(ProvisioningFixtures.employee("E1001", null, null, null),
                ProvisioningFixtures.employee("E1002", null, null, null),
                ProvisioningFixtures.employee("E1003", "eve@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001", "E1002", "E1003", "E9999"), List.of("minagri-email-app"), false, null));

        assertThat(row(report, "E1001").reason()).isEqualTo("account_ambiguous");
        assertThat(row(report, "E1002").reason()).isEqualTo("account_ambiguous");
        assertThat(row(report, "E1003").reason()).isEqualTo("account_other_institution");
        // another institution's employee (absent from MY slice of the mirror) is indistinguishable: not_found
        assertThat(row(report, "E9999").reason()).isEqualTo("not_found");
        assertThat(report.employees()).extracting(ReportRow::employeeNumber)
                .containsExactly("E1001", "E1002", "E1003", "E9999");
        verify(access, never()).admitUser(any(), anyString(), anyString());
        verify(kc, never()).createUser(any());
        verify(mirror).records(eq("minagri"), anyList());
    }

    @Test
    void aClientOutsideScopeRefusesTheWholeRequestBeforeAnyRead() {
        assertThatThrownBy(() -> service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app", "minisante-hmis"), false, null)))
                .isInstanceOf(InstitutionRefusedException.class)
                .satisfies(e -> assertThat(((InstitutionRefusedException) e).refusal().status()).isEqualTo(403));
        verify(mirror, never()).records(anyString(), anyList());
        verify(access, never()).admitUser(any(), anyString(), anyString());
    }

    @Test
    void requestLevelRefusals() {
        EmployeeProvisioningService s = service();
        assertThatThrownBy(() -> s.run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1", "E2", "E3", "E4", "E5", "E6"), List.of("minagri-email-app"), false, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("at most 5");
        assertThatThrownBy(() -> s.run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1"), List.of(), false, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Select at least one application");
        assertThatThrownBy(() -> s.run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1"), List.of("nope"), false, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> s.run(ProvisioningFixtures.ctx("minagri", EnumSet.of(Capability.MANAGE_MEMBERS,
                Capability.MANAGE_MEMBERSHIP)), EmployeeProvisioningService.Command.of(List.of("E1"),
                List.of("minagri-email-app"), false, null)))
                .isInstanceOf(InstitutionRefusedException.class);
    }

    @Test
    void dryRunWritesNothing() {
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app"), true, null));

        assertThat(report.dryRun()).isTrue();
        assertThat(row(report, "E1001").outcome().code()).isEqualTo("created");
        verify(kc, never()).createUser(any());
        verify(access, never()).admitUser(any(), anyString(), anyString());
        verify(mailer, never()).sendWelcome(any());
        verify(mirror, never()).link(anyString(), anyString());
        verify(audit, never()).emit(any(AuditEvent.Builder.class));
    }

    @Test
    void aFailedGrantIsThatEmployeesRowNotTheBatch() {
        when(access.admitUser(sa, "minagri-nid-app", "new-alice@minagri.gov.rw"))
                .thenThrow(ApiException.badGateway("keycloak_error", "policy write refused"));
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", "1198780012345678", null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app", "minagri-nid-app"), false, null));

        ReportRow r = row(report, "E1001");
        assertThat(r.clientsGranted()).containsExactly("minagri-email-app");
        assertThat((List<?>) r.extras().get("grant_errors")).hasSize(1);
        assertThat(report.totals().grants()).isEqualTo(1);
    }

    @Test
    void mailNotConfiguredBindsTheAppsAndRecordsTheDelivery() {
        when(mailer.configured()).thenReturn(false);
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));

        ProvisionReport report = service().run(ProvisioningFixtures.minagri(), EmployeeProvisioningService.Command.of(
                List.of("E1001"), List.of("minagri-email-app"), false, null));

        assertThat(row(report, "E1001").extras()).containsEntry("password_email", "not_configured");
        verify(setup).bindClients("new-alice@minagri.gov.rw", List.of("minagri-email-app"));
        verify(setup).recordDelivery("new-alice@minagri.gov.rw", "not_configured", "provisioning");
        verify(setup, never()).issueUrl(anyString(), anyCollection());
    }

    // ---- EMPLOYEE_PROVISION_DEV_PASSWORD -------------------------------------------------------

    @Test
    void devPasswordIsHonouredOnlyWithDebug(CapturedOutput output) {
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));
        ProvisionReport report = service(true, "Shared-Dev-1").run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of("E1001"), List.of("minagri-email-app"), false, null));
        verify(kc).setPassword("new-alice@minagri.gov.rw", "Shared-Dev-1", false);
        assertThat(row(report, "E1001").extras()).containsEntry("password", "shared_dev")
                .containsEntry("password_email", "skipped_shared_password");
        ArgumentCaptor<UserRepresentation> rep = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(kc).createUser(rep.capture());
        assertThat(rep.getValue().getRequiredActions()).isEmpty();

        assertThat(service(false, "Shared-Dev-1").devPassword()).isNull();
        assertThat(new DevPasswordStartupCheck(new ProvisioningProperties(10, "Shared-Dev-1"),
                ProvisioningFixtures.sso(false)).check()).isEqualTo("ignored");
        assertThat(output.getAll()).contains("is IGNORED").doesNotContain("Shared-Dev-1");
        assertThat(new DevPasswordStartupCheck(new ProvisioningProperties(10, "Shared-Dev-1"),
                ProvisioningFixtures.sso(true)).check()).isEqualTo("honoured");
        assertThat(new DevPasswordStartupCheck(new ProvisioningProperties(10, ""), ProvisioningFixtures.sso(true))
                .check()).isEqualTo("unset");
    }

    @Test
    void devPasswordWithoutDebugKeepsTheRealFlow() {
        mirrorHas(ProvisioningFixtures.employee("E1001", "alice@minagri.gov.rw", null, null));
        ProvisionReport report = service(false, "Shared-Dev-1").run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of("E1001"), List.of("minagri-email-app"), false, null));
        verify(kc, never()).setPassword(anyString(), eq("Shared-Dev-1"), anyBoolean());
        verify(kc, times(1)).setPassword(eq("new-alice@minagri.gov.rw"), anyString(), eq(true));
        assertThat(row(report, "E1001").extras()).containsEntry("password", "set_by_holder");
        assertThat(Map.copyOf(row(report, "E1001").extras()).toString()).doesNotContain("Shared-Dev-1");
    }
}
