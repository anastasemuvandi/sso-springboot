package rw.gov.risa.sso.wrapper.service.institutionadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.WebApplicationException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * The institution-admin services against a real Keycloak 26.2 (shared {@link KeycloakIT} container):
 * create an app, assign access, revoke one person (deny beats the group allow; the person stays in the
 * group), and execute-as-caller reaching Keycloak with the caller's own token.
 */
@Tag("it")
class InstitutionAdminKeycloakIT {

    static ItServices it;
    static InstitutionContext minagri;
    static CallerPrincipal caller;

    @BeforeAll
    static void boot() {
        KeycloakIT.container();
        it = new ItServices();
        var reg = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        minagri = new InstitutionContext("minagri.admin", KeycloakIT.userId("minagri.admin"), "minagri",
                reg.templates().get("institution-admin").render("minagri"), List.of("minagri"), false,
                List.of("inst:minagri:admin"));
        caller = KeycloakIT.caller("minagri.admin");
    }

    static final class Wired {
        final InstitutionAppsService apps;
        final AccessRevocationService revocation;
        final InstitutionUsersService users;

        Wired(boolean asCaller) {
            var props = new DelegationProperties("configs/delegated-permissions/templates.json", "configs/institutions.yaml",
                    true, true, "institution", asCaller);
            DelegatedExecution delegated = new DelegatedExecution(KeycloakIT.executors(), props);
            InstitutionKeycloak kc = new InstitutionKeycloak(new KeycloakDirectory());
            InstitutionMembers members = new InstitutionMembers(kc);
            AccessHolders holders = new AccessHolders(it.access, kc, delegated);
            apps = new InstitutionAppsService(it.scopes, it.access, it.joinKeys, mock(MfaService.class), kc, members,
                    holders, delegated, it.audit);
            @SuppressWarnings("unchecked")
            ObjectProvider<JavaMailSender> noSender = mock(ObjectProvider.class);
            Mailer mailer = new Mailer(noSender, new MailSettings("sso@gov.rw", "http://login", true), "",
                    new MailDeliveryLog(mock(StringRedisTemplate.class)));
            revocation = new AccessRevocationService(members, apps, holders, it.access, delegated,
                    new AccessChangeNotifier(mailer), it.audit);
            users = new InstitutionUsersService(kc, members, delegated, it.joinKeys, it.audit);
        }
    }

    @Test
    void createAssignAndRevokeDenyBeatsTheGroupAllowAndThePersonStaysInTheGroup() {
        Wired w = new Wired(false);
        String app = KeycloakIT.unique("app");
        Map<String, Object> created = w.apps.createApp(minagri, app, List.of("http://localhost:9999/cb"), null, "127.0.0.1");
        String clientId = "minagri-" + app;
        assertThat(created).containsEntry("clientId", clientId);
        ClientRepresentation stored = KeycloakIT.realm().clients().findByClientId(clientId).get(0);
        assertThat(stored.getAttributes()).containsEntry("sso.client.owner_institution", "minagri");

        w.apps.grantAccess(minagri, caller, clientId, null);
        assertThat(it.access.checkLogin("minagri.staff", clientId).hasAccess()).isTrue();
        assertThat(it.access.checkLogin("minisante.staff", clientId).hasAccess()).isFalse();

        String staff = KeycloakIT.userId("minagri.staff");
        Map<String, Object> preview = w.revocation.revoke(minagri, caller, new AccessRevocationService.RevokeCommand(
                List.of(staff), List.of(clientId), false, false, true), null);
        assertThat(preview).containsEntry("preview", true);
        assertThat(it.access.checkLogin("minagri.staff", clientId).hasAccess()).isTrue();

        Map<String, Object> report = w.revocation.revoke(minagri, caller, new AccessRevocationService.RevokeCommand(
                List.of(staff), List.of(clientId), false, false, false), null);
        assertThat(report).containsEntry("total_revocations", 1);
        var check = it.access.checkLogin("minagri.staff", clientId);
        assertThat(check.hasAccess()).isFalse();
        assertThat(check.reason()).isEqualTo(AccessReason.DENIED);
        assertThat(KeycloakIT.realm().users().get(staff).groups()).extracting(GroupRepresentation::getPath)
                .contains("/minagri");
        // the institution's other members are untouched
        assertThat(it.access.checkLogin("minagri.hr", clientId).hasAccess()).isTrue();
    }

    @Test
    void asCallerTheCallersOwnTokenReachesKeycloakWhichDecidesToo() {
        // The wrapper authorises the minagri context; the Keycloak call then carries the CALLER's token. A
        // token that holds no delegated permission (minagri.staff) is refused by Keycloak itself, whether or
        // not the FGAP v2 plan is applied in this shared container — proof the service account was not used.
        // (The positive case, with FGAP applied, is FgapKeycloakDirectIsolationTest.)
        CallerPrincipal noGrant = KeycloakIT.caller("minagri.staff");
        assertThatThrownBy(() -> new Wired(true).users.listUsers(minagri, noGrant, null))
                .isInstanceOfSatisfying(WebApplicationException.class,
                        e -> assertThat(KeycloakErrors.status(e)).isEqualTo(403));
        // the same request with DELEGATED_EXECUTE_AS_CALLER=false runs as the service account, still filtered
        var usernames = new Wired(false).users.listUsers(minagri, caller, null).stream()
                .map(u -> String.valueOf(u.get("username"))).toList();
        assertThat(usernames).contains("minagri.staff", "minagri.planner").doesNotContain("minisante.staff");
    }
}
