package rw.gov.risa.sso.mfa;

import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference gorsso-mfa PolicyTest, unchanged except that the private
 * MfaRequirement is now the shared {@link MfaPolicy} (mfa-policy module).
 */
class PolicyTest {

    @Test
    void unsetOrBlankFallsBackToTheConfiguredDefault() {
        assertTrue(RealmAttributeCondition.isOn(null, "true", true));
        assertTrue(RealmAttributeCondition.isOn("  ", "true", true));
        assertFalse(RealmAttributeCondition.isOn(null, "true", false));
    }

    @Test
    void valueIsComparedCaseInsensitivelyAndTrimmed() {
        assertTrue(RealmAttributeCondition.isOn(" TRUE ", "true", false));
        assertFalse(RealmAttributeCondition.isOn("false", "true", true));
        // Anything that is not the expected value is OFF, not an error.
        assertFalse(RealmAttributeCondition.isOn("yes", "true", true));
    }

    @Test
    void aLoginErrorIsNamedByTheCredentialThatFailed() {
        assertEquals("auth.mfa.otp_failed", AuditMapping.userEvent("LOGIN_ERROR", Map.of(), "otp"));
        assertEquals("auth.mfa.recovery_code_failed",
                AuditMapping.userEvent("LOGIN_ERROR", Map.of(), "recovery-authn-codes"));
        assertEquals("auth.login.failed", AuditMapping.userEvent("LOGIN_ERROR", Map.of(), "password"));
        assertEquals("auth.login.failed", AuditMapping.userEvent("LOGIN_ERROR", null, null));
    }

    @Test
    void loginItselfIsNotForwarded() {
        assertNull(AuditMapping.userEvent("LOGIN", Map.of("credential_type", "recovery-authn-codes"), null));
    }

    @Test
    void otpEnrolmentIsForwardedOnceNotTwice() {
        assertEquals("auth.mfa.totp_enrolled", AuditMapping.userEvent("UPDATE_TOTP", Map.of(), null));
        assertNull(AuditMapping.userEvent("UPDATE_CREDENTIAL", Map.of("credential_type", "otp"), null));
        assertEquals("auth.mfa.totp_removed", AuditMapping.userEvent("REMOVE_TOTP", Map.of(), null));
        assertNull(AuditMapping.userEvent("REMOVE_CREDENTIAL", Map.of("credential_type", "otp"), null));
    }

    @Test
    void recoveryCodeCredentialChangesAreForwarded() {
        assertEquals("auth.mfa.recovery_codes_configured",
                AuditMapping.userEvent("UPDATE_CREDENTIAL", Map.of("credential_type", "recovery-authn-codes"), null));
        assertEquals("auth.mfa.recovery_codes_removed",
                AuditMapping.userEvent("REMOVE_CREDENTIAL", Map.of("credential_type", "recovery-authn-codes"), null));
    }

    @Test
    void unrelatedEventsAreNotForwarded() {
        assertNull(AuditMapping.userEvent("CODE_TO_TOKEN", Map.of(), null));
        assertNull(AuditMapping.userEvent("UPDATE_CREDENTIAL", Map.of("credential_type", "password"), null));
    }

    @Test
    void failuresAndRemovalsAreWarnings() {
        assertEquals("WARNING", AuditMapping.severity("auth.mfa.otp_failed"));
        assertEquals("WARNING", AuditMapping.severity("auth.mfa.recovery_code_failed"));
        assertEquals("WARNING", AuditMapping.severity("auth.mfa.recovery_code_used"));
        assertEquals("WARNING", AuditMapping.severity("auth.mfa.totp_removed"));
        assertEquals("INFO", AuditMapping.severity("auth.mfa.totp_enrolled"));
    }

    @Test
    void credentialConditionMatchesPresenceOrAbsence() {
        assertTrue(CredentialCondition.matches(true, true));
        assertTrue(CredentialCondition.matches(false, false));
        assertFalse(CredentialCondition.matches(false, true));
        assertFalse(CredentialCondition.matches(true, false));
    }

    @Test
    void anAdminCredentialDeletionIsForwarded() {
        assertEquals("admin.mfa.credential_removed",
                AuditMapping.adminEvent("USER", "DELETE", "users/u1/credentials/c1"));
        assertEquals("admin.mfa.credential_removed",
                AuditMapping.adminEvent("USER", "ACTION", "users/u1/credentials/c1"));
        assertNull(AuditMapping.adminEvent("USER", "ACTION", "users/u1/credentials/c1/moveToFirst"));
        assertNull(AuditMapping.adminEvent("USER", "DELETE", "users/u1"));
        assertNull(AuditMapping.adminEvent("USER", "UPDATE", "users/u1/credentials/c1"));
        assertEquals("WARNING", AuditMapping.severity("admin.mfa.credential_removed"));
    }

    @Test
    void modeFallsBackToTheLegacySwitch() {
        assertEquals("all", MfaPolicy.mode(null, null));
        assertEquals("all", MfaPolicy.mode(null, "true"));
        assertEquals("off", MfaPolicy.mode(null, "false"));
        assertEquals("per_client", MfaPolicy.mode(" PER_CLIENT ", "false"));
        // an unknown mode fails closed
        assertEquals("all", MfaPolicy.mode("sometimes", "false"));
    }

    @Test
    void perClientDefaultsToRequired() {
        assertTrue(MfaPolicy.applies("per_client", null, null));
        assertTrue(MfaPolicy.applies("per_client", null, "true"));
        assertFalse(MfaPolicy.applies("per_client", null, "false"));
        assertTrue(MfaPolicy.applies("all", null, "false"));
        assertFalse(MfaPolicy.applies("off", null, null));
    }
}
