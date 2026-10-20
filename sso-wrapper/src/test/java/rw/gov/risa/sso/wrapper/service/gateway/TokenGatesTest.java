package rw.gov.risa.sso.wrapper.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.gateway.TokenGates.Door;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mfa.MfaBulkEnrolment;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * {@link TokenGates} with the REAL password-grant MFA rule (the reference's acr check, shared
 * {@code mfa-policy}): TOTP applies to the client, so a token with acr 1 is refused unless the person is
 * {@code mfa-exempt}; acr 2 passes; a switch that cannot be read fails open (as the rule says).
 */
class TokenGatesTest {

    TokenVerifier verifier = mock(TokenVerifier.class);
    ClientAccessService access = mock(ClientAccessService.class);
    JoinKeyService joinKeys = mock(JoinKeyService.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    MfaService mfa;
    TokenGates gates;

    @BeforeEach
    void setUp() {
        doCallRealMethod().when(audit).emit(any(AuditEvent.Builder.class));
        mfa = spy(new MfaService(mock(KeycloakExecutors.class), mock(KeycloakDirectory.class),
                mock(MfaBulkEnrolment.class), audit));
        doReturn(true).when(mfa).totpApplies("app");
        gates = new TokenGates(verifier, access, joinKeys, mfa);
    }

    private void tokenWith(Object acr, String... roles) {
        when(verifier.verifyUserToken("t")).thenReturn(new TokenVerifier.Verified("jdoe", "u1", "app", "s1",
                Map.of("acr", acr, "realm_access", Map.of("roles", List.of(roles)))));
    }

    @Test
    void passwordTokenWithoutTotpIsRefusedWhereTotpApplies() {
        tokenWith("1", "default-roles-government-internal");
        assertThatThrownBy(() -> gates.pass(Door.LOGIN, "t", "app", "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(403);
                    assertThat(e.error()).isEqualTo("mfa_required");
                });
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.MFA_PASSWORD_GRANT_REFUSED
                && "jdoe".equals(e.actor()) && "/auth/login".equals(e.extra().get("door"))));
    }

    @Test
    void refreshOfAPasswordTokenIsRefusedToo() {
        tokenWith("1");
        assertThatThrownBy(() -> gates.pass(Door.TOKEN_REFRESH, "t", "app", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("mfa_required"));
    }

    @Test
    void exemptPersonPasses() {
        tokenWith("1", "mfa-exempt");
        assertThat(gates.pass(Door.AUTHORIZE_INTERNAL, "t", "app", null).username()).isEqualTo("jdoe");
    }

    @Test
    void tokenThatPresentedTotpPasses() {
        tokenWith("2");
        gates.pass(Door.TOKEN_WRAPPER_CODE, "t", "app", null);
    }

    @Test
    void browserCodeIsNotGatedByThePasswordRule() {
        tokenWith("1");
        gates.pass(Door.TOKEN_BROWSER_CODE, "t", "app", null);
        verify(mfa, never()).enforcePasswordGrant(any(), any(), any(), any(), any());
    }

    @Test
    void unreadableSwitchFailsOpen() {
        tokenWith("1");
        doThrow(new IllegalStateException("keycloak down")).when(mfa).totpApplies("app");
        gates.pass(Door.LOGIN, "t", "app", null);
    }

    @Test
    void gatesRunInOrderAccessThenJoinKeyThenMfa() {
        tokenWith("2");
        gates.pass(Door.LOGIN, "t", "app", "ip");
        InOrder o = inOrder(verifier, access, joinKeys, mfa);
        o.verify(verifier).verifyUserToken("t");
        o.verify(access).enforceLogin("jdoe", "app", "ip", "password");
        o.verify(joinKeys).enforceLogin("jdoe", "app", "ip", "password");
        o.verify(mfa).enforcePasswordGrant(any(), any(), any(), any(), any());
    }

    @Test
    void accessRefusalStopsBeforeTheOtherGates() {
        tokenWith("2");
        doThrow(ApiException.forbidden("access_denied", "no")).when(access).enforceLogin(any(), any(), any(), any());
        assertThatThrownBy(() -> gates.pass(Door.LOGIN, "t", "app", null)).isInstanceOf(ApiException.class);
        verify(joinKeys, never()).enforceLogin(any(), any(), any(), any());
    }

    @Test
    void tokenIssuedToAnotherClientIsRefused() {
        tokenWith("2");
        assertThatThrownBy(() -> gates.pass(Door.TOKEN_REFRESH, "t", "other-app", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_grant"));
        verify(access, never()).enforceLogin(any(), any(), any(), any());
    }
}
