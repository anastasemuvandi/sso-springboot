package rw.gov.risa.sso.wrapper.service.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;
import rw.gov.risa.sso.wrapper.support.RedisIT;

/**
 * Mode + exemptions against the shared realm. Restores the realm (mode off, browser flow) afterwards and
 * never starts a real bulk enrolment (that would put CONFIGURE_TOTP on every shared test user).
 */
@Tag("it")
class MfaServiceIT {

    static MfaService mfa;
    static MfaBulkEnrolment bulk;
    static MfaFlowInstaller installer;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        KeycloakDirectory dir = new KeycloakDirectory();
        AuditEmitter audit = mock(AuditEmitter.class);
        bulk = new MfaBulkEnrolment(KeycloakIT.executors(), dir, RedisIT.template(), audit);
        mfa = new MfaService(KeycloakIT.executors(), dir, bulk, audit);
        installer = new MfaFlowInstaller(KeycloakIT.executors(), dir);
    }

    @AfterAll
    static void restore() {
        RealmRepresentation rep = KeycloakIT.realm().toRepresentation();
        rep.setBrowserFlow("browser");
        rep.getAttributes().put(MfaConstants.MODE_ATTRIBUTE, "off");
        KeycloakIT.realm().update(rep);
    }

    @Test
    void modeNeedsTheFlowThenPerClientApplies() {
        assertThat(mfa.getState()).containsEntry("mode", "off").containsEntry("flow_ready", false);
        assertThatThrownBy(() -> mfa.setMode("per_client", "platform.super", null))
                .isInstanceOf(ApiException.class).hasMessageContaining("browser-totp");
        assertThatThrownBy(() -> mfa.setMode("sometimes", "platform.super", null))
                .isInstanceOf(ApiException.class);

        // A stand-in browser-totp flow (the real one needs the gorsso-mfa providers).
        KeycloakIT.realm().flows().copy("browser", Map.of("newName", MfaConstants.MFA_FLOW_ALIAS)).close();
        RealmRepresentation rep = KeycloakIT.realm().toRepresentation();
        rep.setBrowserFlow(MfaConstants.MFA_FLOW_ALIAS);
        KeycloakIT.realm().update(rep);

        var change = mfa.setMode("per_client", "platform.super", "127.0.0.1");
        assertThat(change.changed()).isTrue();
        assertThat(change.bulkEnrol()).isNull();
        assertThat(mfa.getState()).containsEntry("mode", "per_client").containsEntry("effective", true);

        String client = KeycloakIT.unique("minagri-mfa");
        KeycloakIT.createClient(client);
        assertThat(mfa.totpApplies(client)).isTrue(); // unset = required
        var c = mfa.setClientRequirement(client, false, "platform.super", null, null);
        assertThat(c.changed()).isTrue();
        assertThat(mfa.clientRequirement(client).appliesNow()).isFalse();
        mfa.invalidate();
        assertThat(mfa.totpApplies(client)).isFalse();

        // Password grant door: required client, no acr 2, not exempt → refused.
        mfa.setClientRequirement(client, true, "platform.super", null, null);
        mfa.invalidate();
        assertThat(mfa.passwordGrantRefusal(Map.of("acr", "1"), client)).isPresent();
        assertThat(mfa.passwordGrantRefusal(Map.of("acr", "1", "realm_access",
                Map.of("roles", List.of("mfa-exempt"))), client)).isEmpty();

        assertThat(mfa.setMode("off", "platform.super", null).mode()).isEqualTo("off");
    }

    @Test
    void exemptionClearsPendingConfigureTotpAndIsRevocable() {
        String username = KeycloakIT.unique("mfa-exempt");
        String uid = KeycloakIT.createUser(username, "minagri");
        UserRepresentation u = KeycloakIT.realm().users().get(uid).toRepresentation();
        u.setRequiredActions(List.of(MfaConstants.CONFIGURE_TOTP));
        KeycloakIT.realm().users().get(uid).update(u);

        KeycloakIT.ensureRealmRole(MfaConstants.EXEMPT_ROLE);
        assertThatThrownBy(() -> mfa.grantExemption(uid, "too short", "platform.super", null))
                .isInstanceOf(ApiException.class);
        var e = mfa.grantExemption(uid, "Hardware token pending, approved by DG until March", "platform.super", null);
        assertThat(e.grantedBy()).isEqualTo("platform.super");
        UserRepresentation after = KeycloakIT.realm().users().get(uid).toRepresentation();
        assertThat(after.getRequiredActions()).doesNotContain(MfaConstants.CONFIGURE_TOTP);
        assertThat(after.getAttributes()).containsKey(MfaConstants.EXEMPT_REASON_ATTRIBUTE);
        assertThat(mfa.listExemptions()).extracting(MfaService.Exemption::userId).contains(uid);

        // The bulk job never touches an exempt person (dry run: nothing written).
        var summary = bulk.run(true);
        assertThat(summary.skipped).containsKey("exempt");

        mfa.revokeExemption(uid, "platform.super", null);
        assertThat(mfa.listExemptions()).extracting(MfaService.Exemption::userId).doesNotContain(uid);
        assertThat(KeycloakIT.realm().users().get(uid).toRepresentation().getAttributes())
                .doesNotContainKey(MfaConstants.EXEMPT_REASON_ATTRIBUTE);
    }

    @Test
    void installerRefusesWithoutTheProviders() {
        var report = installer.install(MfaFlowInstaller.Options.applyDefaults());
        assertThat(report.applied()).isFalse();
        assertThat(report.missingProviders()).contains("gorsso-mfa-step-up");
    }
}
