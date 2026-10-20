package rw.gov.risa.sso.wrapper.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * Employee provisioning against a real Keycloak 26.2 ({@link KeycloakIT}): the create path (username = email,
 * UPDATE_PASSWORD, a credential exists but nobody was told it, institution group membership, a per-user
 * grant) and the existing-account path (nothing about the account changes; only the grant).
 */
@Tag("it")
class EmployeeProvisioningIT {

    static ItServices s;
    static EmployeeMirror mirror = mock(EmployeeMirror.class);
    static SetupLinkService setup = mock(SetupLinkService.class);
    static Mailer mailer = mock(Mailer.class);
    static EmployeeProvisioningService service;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        s = new ItServices();
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendWelcome(any())).thenReturn(DeliveryResult.sent());
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
        when(setup.issueUrl(anyString(), anyCollection())).thenReturn("http://wrapper/account/setup?token=t");
        service = new EmployeeProvisioningService(new ProvisioningKeycloak(KeycloakIT.executors(), s.directory),
                mirror, s.access, s.scopes, s.joinKeys, new rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyProperties(
                        true, false, true, 200), setup, mailer, s.audit, new ProvisioningProperties(100, ""),
                KeycloakIT.ssoProperties());
    }

    /** No phone / NID: identifier uniqueness across the shared realm is not this test's subject. */
    static rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord rec(String number, String email, String uid) {
        return new rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord(number, email, null, null, "New",
                "Hire", "minagri", "active", uid);
    }

    static String emailClient() {
        String client = KeycloakIT.unique("minagri-prov");
        KeycloakIT.createClient(client, Map.of(JoinKeys.ATTR_JOIN_COLUMN, "email"));
        return client;
    }

    @Test
    void createsTheAccountTheSpecWayAndGrantsPerUser() {
        String client = emailClient();
        String number = KeycloakIT.unique("E").toUpperCase();
        String email = KeycloakIT.unique("new.hire") + "@minagri.gov.rw";
        when(mirror.records(eq("minagri"), eq(List.of(number)))).thenReturn(List.of(
                rec(number, email, null)));

        ProvisionReport report = service.run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of(number), List.of(client), false, null));

        ReportRow row = report.employees().get(0);
        assertThat(row.outcome().code()).isEqualTo("created");
        String uid = row.userId();
        UserRepresentation u = KeycloakIT.realm().users().get(uid).toRepresentation();
        assertThat(u.getUsername()).isEqualTo(email.toLowerCase());
        assertThat(u.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(u.getAttributes()).containsEntry("institution", List.of("minagri"))
                .containsEntry("employee_number", List.of(number));
        // a credential exists (not claimable by self-service reset) — and it is not in the report
        assertThat(KeycloakIT.realm().users().get(uid).credentials()).extracting("type").contains("password");
        assertThat(row.extras().toString()).doesNotContainIgnoringCase("Aa1!");
        // membership of the institution group (never how access is granted)
        assertThat(KeycloakIT.realm().users().get(uid).groups()).extracting(GroupRepresentation::getPath)
                .contains("/minagri");
        ClientAssignment a = s.access.assignment(s.access.serviceAccount(), client);
        assertThat(a.userIds()).containsExactly(uid);
        assertThat(a.groupIds()).isEmpty();
        verify(mirror).link(number, uid);

        // idempotent: a re-run resolves the account (employee_number attribute), grants nothing new
        when(mirror.records(eq("minagri"), eq(List.of(number)))).thenReturn(List.of(
                rec(number, email, uid)));
        ProvisionReport again = service.run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of(number), List.of(client), false, null));
        assertThat(again.employees().get(0).reason()).isEqualTo("nothing_to_assign");
        assertThat(again.employees().get(0).clientsSkipped()).containsExactly(client);
    }

    @Test
    void anExistingAccountIsOnlyGrantedNeverAltered() {
        String client = emailClient();
        String username = KeycloakIT.unique("existing");
        String uid = KeycloakIT.createUser(username, "minagri");
        UserRepresentation before = KeycloakIT.realm().users().get(uid).toRepresentation();
        String number = KeycloakIT.unique("E").toUpperCase();
        when(mirror.records(eq("minagri"), eq(List.of(number)))).thenReturn(List.of(
                rec(number, username + "@it.gov.rw", null)));

        ProvisionReport report = service.run(ProvisioningFixtures.minagri(),
                EmployeeProvisioningService.Command.of(List.of(number), List.of(client), false, null));

        ReportRow row = report.employees().get(0);
        assertThat(row.account().code()).isEqualTo("existing");
        assertThat(row.userId()).isEqualTo(uid);
        UserRepresentation after = KeycloakIT.realm().users().get(uid).toRepresentation();
        assertThat(after.getUsername()).isEqualTo(before.getUsername());
        assertThat(after.getEmail()).isEqualTo(before.getEmail());
        assertThat(after.getRequiredActions()).isEqualTo(before.getRequiredActions());
        assertThat(after.getAttributes()).isEqualTo(before.getAttributes());
        // the password still works: nothing about the credential changed
        assertThat(KeycloakIT.token(username)).isNotBlank();
        assertThat(s.access.assignment(s.access.serviceAccount(), client).userIds()).containsExactly(uid);
        verify(mailer, never()).sendWelcome(org.mockito.ArgumentMatchers.argThat(m -> uid.equals(m.userId())));
        verify(mirror).link(number, uid);
        verify(mirror, never()).records(eq("minisante"), anyList());
    }
}
