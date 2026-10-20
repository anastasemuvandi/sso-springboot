package rw.gov.risa.sso.mfa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exhaustive table for which Keycloak events reach the audit trail, under which name and severity. */
class AuditMappingTest {

    /** {@code NULL} = null; credential_type detail column empty = no detail. */
    @ParameterizedTest(name = "{0} credential_type={1} selected={2} -> {3}")
    @CsvSource(nullValues = "NULL", value = {
            // LOGIN_ERROR named by the credential the form checked
            "LOGIN_ERROR,             NULL,                 otp,                  auth.mfa.otp_failed",
            "LOGIN_ERROR,             NULL,                 recovery-authn-codes, auth.mfa.recovery_code_failed",
            "LOGIN_ERROR,             NULL,                 password,             auth.login.failed",
            "LOGIN_ERROR,             NULL,                 NULL,                 auth.login.failed",
            "LOGIN_ERROR,             otp,                  NULL,                 auth.login.failed",
            // LOGIN itself is never forwarded (the re-enrol authenticator posts recovery use)
            "LOGIN,                   recovery-authn-codes, NULL,                 NULL",
            "LOGIN,                   NULL,                 otp,                  NULL",
            // OTP enrolment / removal: the deprecated pair only, never twice
            "UPDATE_TOTP,             NULL,                 NULL,                 auth.mfa.totp_enrolled",
            "REMOVE_TOTP,             NULL,                 NULL,                 auth.mfa.totp_removed",
            "UPDATE_CREDENTIAL,       otp,                  NULL,                 NULL",
            "REMOVE_CREDENTIAL,       otp,                  NULL,                 NULL",
            // recovery codes
            "UPDATE_CREDENTIAL,       recovery-authn-codes, NULL,                 auth.mfa.recovery_codes_configured",
            "REMOVE_CREDENTIAL,       recovery-authn-codes, NULL,                 auth.mfa.recovery_codes_removed",
            // enrolment errors for second factors only
            "UPDATE_CREDENTIAL_ERROR, otp,                  NULL,                 auth.mfa.enrol_failed",
            "UPDATE_CREDENTIAL_ERROR, recovery-authn-codes, NULL,                 auth.mfa.enrol_failed",
            "UPDATE_CREDENTIAL_ERROR, password,             NULL,                 NULL",
            "UPDATE_CREDENTIAL_ERROR, NULL,                 NULL,                 NULL",
            // unrelated
            "UPDATE_CREDENTIAL,       password,             NULL,                 NULL",
            "REMOVE_CREDENTIAL,       password,             NULL,                 NULL",
            "UPDATE_CREDENTIAL,       NULL,                 NULL,                 NULL",
            "CODE_TO_TOKEN,           NULL,                 NULL,                 NULL",
            "LOGOUT,                  NULL,                 NULL,                 NULL",
            "UPDATE_PASSWORD,         NULL,                 NULL,                 NULL",
            "REGISTER,                NULL,                 NULL,                 NULL",
    })
    void userEvents(String type, String credentialType, String selected, String expected) {
        Map<String, String> details = new HashMap<>();
        if (credentialType != null) {
            details.put("credential_type", credentialType);
        }
        assertThat(AuditMapping.userEvent(type, details, selected)).isEqualTo(expected);
    }

    @Test
    void nullDetailsAreTolerated() {
        assertThat(AuditMapping.userEvent("UPDATE_CREDENTIAL", null, null)).isNull();
        assertThat(AuditMapping.userEvent("LOGIN_ERROR", null, "otp")).isEqualTo("auth.mfa.otp_failed");
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(nullValues = "NULL", value = {
            "USER,  DELETE, users/u1/credentials/c1,             admin.mfa.credential_removed",
            "USER,  ACTION, users/u1/credentials/c1,             admin.mfa.credential_removed",
            "USER,  ACTION, users/u1/credentials/c1/moveToFirst, NULL",
            "USER,  ACTION, users/u1/credentials/c1/userLabel,   NULL",
            "USER,  DELETE, users/u1,                            NULL",
            "USER,  UPDATE, users/u1/credentials/c1,             NULL",
            "USER,  CREATE, users/u1/credentials/c1,             NULL",
            "CLIENT, DELETE, users/u1/credentials/c1,            NULL",
            "USER,  DELETE, NULL,                                NULL",
            "NULL,  NULL,   NULL,                                NULL",
            "USER,  DELETE, /users/u1/credentials/c1,            NULL",
    })
    void adminEvents(String resourceType, String operation, String path, String expected) {
        assertThat(AuditMapping.adminEvent(resourceType, operation, path)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {
            "auth.mfa.otp_failed,               WARNING",
            "auth.mfa.recovery_code_failed,     WARNING",
            "auth.mfa.recovery_code_used,       WARNING",
            "auth.mfa.totp_removed,             WARNING",
            "auth.mfa.recovery_codes_removed,   WARNING",
            "auth.mfa.enrol_failed,             WARNING",
            "auth.login.failed,                 INFO",     // ".failed", not "_failed": as the reference
            "admin.mfa.credential_removed,      WARNING",
            "auth.mfa.totp_enrolled,            INFO",
            "auth.mfa.recovery_codes_configured, INFO",
            "admin.mfa.switch_observed,         INFO",
            "NULL,                              INFO",
    })
    void severity(String type, String expected) {
        assertThat(AuditMapping.severity(type)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} rep={1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
            "REALM_ROLE_MAPPING, '[{\"id\":\"r1\",\"name\":\"mfa-exempt\"}]', true",
            "REALM_ROLE_MAPPING, '[{\"id\":\"r1\",\"name\":\"employee\"}]',   false",
            "REALM_ROLE_MAPPING, '[{\"name\":\"mfa-exempt-old\"}]',           false",
            "REALM_ROLE_MAPPING, NULL,                                         false",
            "CLIENT_ROLE_MAPPING, '[{\"name\":\"mfa-exempt\"}]',              false",
            "NULL,               '[{\"name\":\"mfa-exempt\"}]',               false",
    })
    void exemptRoleMapping(String resourceType, String representation, boolean expected) {
        assertThat(AuditMapping.namesExemptRole(resourceType, representation)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "before={0} now={1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL,       all,        false",  // first sight after restart only primes
            "all,        all,        false",
            "all,        off,        true",
            "off,        per_client, true",
            "per_client, all,        true",
    })
    void switchChanged(String before, String now, boolean expected) {
        assertThat(AuditMapping.switchChanged(before, now)).isEqualTo(expected);
    }
}
