package rw.gov.risa.sso.mfa.policy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven port of every policy case in the reference: PolicyTest.java
 * (gorsso-mfa) and tests/e2e/test_mfa_totp.py (TestSwitchReading, TestReason,
 * TestPasswordGrantRefusal, TestBulkSkipRules, TestPerClient).
 *
 * <p>In @CsvSource, {@code NULL} is null and {@code ''} is the empty string.
 */
class MfaPolicyTest {

    @Nested
    class EffectiveMode {

        @ParameterizedTest(name = "mode={0} legacy={1} -> {2}")
        @CsvSource(nullValues = "NULL", value = {
                // reference PolicyTest.modeFallsBackToTheLegacySwitch / TestPerClient
                "NULL,         NULL,     all",
                "NULL,         true,     all",
                "NULL,         false,    off",
                "' PER_CLIENT ', false,  per_client",
                "sometimes,    false,    all",      // unknown mode fails closed
                // explicit values, normalised
                "off,          NULL,     off",
                "OFF,          true,     off",
                "'  off  ',    true,     off",
                "all,          false,    all",
                "ALL,          NULL,     all",
                "per_client,   NULL,     per_client",
                "Per_Client,   true,     per_client",
                "per-client,   NULL,     all",      // not a recognised spelling -> all
                "perclient,    false,    all",
                // blank mode = unset -> legacy
                "'',           NULL,     all",
                "'   ',        false,    off",
                "'',           true,     all",
                // legacy parsing: unset/blank = on, only "false" (any case, trimmed) = off
                "NULL,         '',       all",
                "NULL,         '  ',     all",
                "NULL,         FALSE,    off",
                "NULL,         ' False ', off",
                "NULL,         TRUE,     all",
                "NULL,         yes,      all",      // junk legacy value fails closed (Keycloak reading)
                "NULL,         0,        all",
                "NULL,         no,       all",
        })
        void table(String mode, String legacy, String expected) {
            assertThat(MfaPolicy.mode(mode, legacy)).isEqualTo(expected);
            assertThat(MfaPolicy.effectiveMode(mode, legacy).value()).isEqualTo(expected);
        }

        @Test
        void fromRealmAttributeMapStringOrListValues() {
            assertThat(MfaPolicy.effectiveMode(Map.of())).isEqualTo(MfaMode.ALL);
            assertThat(MfaPolicy.effectiveMode(Map.of("mfa_totp_enforced", "false"))).isEqualTo(MfaMode.OFF);
            assertThat(MfaPolicy.effectiveMode(Map.of("mfa_totp_mode", List.of("per_client"))))
                    .isEqualTo(MfaMode.PER_CLIENT);
            assertThat(MfaPolicy.effectiveMode(Map.of("mfa_totp_mode", List.of(), "mfa_totp_enforced", "false")))
                    .isEqualTo(MfaMode.OFF);
        }

        @ParameterizedTest
        @CsvSource(nullValues = "NULL", value = {"NULL,true", "'',true", "'  ',true", "true,true", "TRUE,true",
                "false,false", "' FALSE ',false", "yes,true", "no,true"})
        void legacyEnforced(String raw, boolean expected) {
            assertThat(MfaPolicy.legacyEnforced(raw)).isEqualTo(expected);
        }
    }

    @Nested
    class AttributeMatches {

        // reference PolicyTest: RealmAttributeCondition.isOn, and test_mfa_totp.py
        // test_unset_or_blank_is_on_exactly_as_keycloak_reads_it (is_enforced == isOn(v,"true",true))
        @ParameterizedTest(name = "isOn({0},{1},{2}) = {3}")
        @CsvSource(nullValues = "NULL", value = {
                "NULL,     true, true,  true",
                "'',       true, true,  true",
                "'  ',     true, true,  true",
                "NULL,     true, false, false",
                "' TRUE ', true, false, true",
                "false,    true, true,  false",
                "yes,      true, true,  false",
                "' True ', true, true,  true",
        })
        void table(String value, String expected, boolean whenUnset, boolean result) {
            assertThat(MfaPolicy.attributeMatches(value, expected, whenUnset)).isEqualTo(result);
        }
    }

    @Nested
    class PerClient {

        @ParameterizedTest(name = "clientRequires({0}) = {1}")
        @CsvSource(nullValues = "NULL", value = {
                "NULL, true", "'', true", "'  ', true", "true, true", "TRUE, true",
                "false, false", "FALSE, false", "' false ', false", "' False\t', false",
                "no, true", "0, true", "off, true", "yes, true"})
        void clientRequires(String value, boolean expected) {
            assertThat(MfaPolicy.clientRequires(value)).isEqualTo(expected);
        }

        // reference PolicyTest.perClientDefaultsToRequired + TestPerClient.test_rules_agree_with_keycloak
        @ParameterizedTest(name = "applies(mode={0}, legacy={1}, client={2}) = {3}")
        @CsvSource(nullValues = "NULL", value = {
                "per_client, NULL,  NULL,   true",
                "per_client, NULL,  true,   true",
                "per_client, NULL,  false,  false",
                "per_client, NULL,  '',     true",
                "per_client, NULL,  ' FALSE ', false",
                "all,        NULL,  false,  true",
                "off,        NULL,  NULL,   false",
                "off,        NULL,  true,   false",
                "NULL,       NULL,  false,  true",    // unset everything = all
                "NULL,       false, NULL,   false",   // legacy off
                "NULL,       true,  false,  true",
                "garbage,    false, false,  true",    // unknown mode = all
                "' Per_Client ', NULL, false, false",
        })
        void applies(String mode, String legacy, String client, boolean expected) {
            assertThat(MfaPolicy.applies(mode, legacy, client)).isEqualTo(expected);
            assertThat(MfaPolicy.applies(Optional.ofNullable(mode), Optional.ofNullable(legacy),
                    Optional.ofNullable(client))).isEqualTo(expected);
        }

        @Test
        void appliesByMode() {
            assertThat(MfaPolicy.applies(MfaMode.PER_CLIENT, null)).isTrue();
            assertThat(MfaPolicy.applies(MfaMode.PER_CLIENT, "false")).isFalse();
            assertThat(MfaPolicy.applies(MfaMode.ALL, "false")).isTrue();
            assertThat(MfaPolicy.applies(MfaMode.OFF, null)).isFalse();
            assertThat(MfaPolicy.applies((MfaMode) null, "false")).as("null mode fails closed").isTrue();
        }

        @Test
        void clientValueFromRepresentation() {
            assertThat(MfaPolicy.clientValue(Map.of("attributes", Map.of("sso.client.mfa_required", "false"))))
                    .contains("false");
            assertThat(MfaPolicy.clientValue(Map.of("attributes", Map.of("institution", "minagri")))).isEmpty();
            assertThat(MfaPolicy.clientValue(Map.of("clientId", "x"))).isEmpty();
            assertThat(MfaPolicy.clientValue(null)).isEmpty();
            Map<String, Object> nullAttrs = new HashMap<>();
            nullAttrs.put("attributes", null);
            assertThat(MfaPolicy.clientValue(nullAttrs)).isEmpty();
        }

        @Test
        void delegatedAdminsMayOnlyTurnItOn() {
            assertThat(MfaPolicy.delegatedChangeRefusal(true)).isEmpty();
            assertThat(MfaPolicy.delegatedChangeRefusal(false)).get().asString().contains("super admin");
        }

        @Test
        void encode() {
            assertThat(MfaPolicy.encode(true)).isEqualTo("true");
            assertThat(MfaPolicy.encode(false)).isEqualTo("false");
        }
    }

    @Nested
    class RequestedMode {

        @ParameterizedTest
        @CsvSource({"off,OFF", "' ALL ',ALL", "per_client,PER_CLIENT", "Per_Client,PER_CLIENT"})
        void accepted(String raw, MfaMode expected) {
            assertThat(MfaPolicy.requestedMode(raw)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource(nullValues = "NULL", value = {"sometimes", "''", "NULL", "per-client", "true"})
        void refused(String raw) {
            assertThatThrownBy(() -> MfaPolicy.requestedMode(raw))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("off, per_client, all");
        }
    }

    @Nested
    class Reason {

        @Test
        void aRealJustificationIsRequired() {
            assertThatThrownBy(() -> MfaPolicy.validateReason(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MfaPolicy.validateReason("   because   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MfaPolicy.validateReason("x".repeat(MfaConstants.MAX_REASON_LENGTH + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("500");
        }

        @Test
        void whitespaceIsNormalised() {
            assertThat(MfaPolicy.validateReason("  Blind user;\n screen reader cannot scan QR "))
                    .isEqualTo("Blind user; screen reader cannot scan QR");
        }

        @Test
        void boundaries() {
            assertThat(MfaPolicy.validateReason("x".repeat(15))).hasSize(15);
            assertThatThrownBy(() -> MfaPolicy.validateReason("x".repeat(14)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("15");
            assertThat(MfaPolicy.validateReason("x".repeat(500))).hasSize(500);
            // collapsing happens before the length check
            assertThatThrownBy(() -> MfaPolicy.validateReason("a      b      c      d"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class PasswordGrant {

        private final Map<String, Object> plain = Map.of("acr", "1", "realm_access", Map.of("roles", List.of("employee")));

        @Test
        void refusedOnlyWhileEffective() {
            assertThat(MfaPolicy.passwordGrantRefusal(plain, true)).isPresent();
            assertThat(MfaPolicy.passwordGrantRefusal(plain, false)).isEmpty();
        }

        @Test
        void exemptPersonIsLetThrough() {
            Map<String, Object> claims = Map.of("acr", "1",
                    "realm_access", Map.of("roles", List.of("employee", "mfa-exempt")));
            assertThat(MfaPolicy.passwordGrantRefusal(claims, true)).isEmpty();
            assertThat(MfaPolicy.isExemptClaims(claims)).isTrue();
        }

        @Test
        void aTokenFromATotpLoginIsLetThrough() {
            assertThat(MfaPolicy.passwordGrantRefusal(
                    Map.of("acr", "2", "realm_access", Map.of("roles", List.of())), true)).isEmpty();
        }

        static Stream<Arguments> acrValues() {
            return Stream.of(
                    Arguments.of(null, false), Arguments.of("loa2", false), Arguments.of("", false),
                    Arguments.of("1", false), Arguments.of("0", false), Arguments.of("2", true),
                    Arguments.of(" 2 ", true), Arguments.of("3", true), Arguments.of(2, true),
                    Arguments.of(1L, false), Arguments.of(2.0, false), Arguments.of(true, false));
        }

        @ParameterizedTest(name = "acr={0} -> {1}")
        @MethodSource("acrValues")
        void presentedTotp(Object acr, boolean expected) {
            assertThat(MfaPolicy.presentedTotp(acr)).isEqualTo(expected);
            Map<String, Object> claims = new HashMap<>();
            claims.put("acr", acr);
            assertThat(MfaPolicy.presentedTotpClaims(claims)).isEqualTo(expected);
        }

        @Test
        void missingOrJunkAcrIsNotMfa() {
            assertThat(MfaPolicy.presentedTotpClaims(Map.of())).isFalse();
            assertThat(MfaPolicy.presentedTotpClaims(null)).isFalse();
        }

        @Test
        void malformedRealmAccessIsNotExempt() {
            assertThat(MfaPolicy.isExemptClaims(Map.of())).isFalse();
            assertThat(MfaPolicy.isExemptClaims(Map.of("realm_access", "mfa-exempt"))).isFalse();
            assertThat(MfaPolicy.isExemptClaims(Map.of("realm_access", Map.of("roles", "mfa-exempt")))).isFalse();
            assertThat(MfaPolicy.isExemptClaims(null)).isFalse();
        }
    }

    @Nested
    class Exemption {

        @Test
        void exemptRoleGuard() {
            assertThat(MfaPolicy.namesExemptRole(List.of("employee", "inst:minagri:staff"))).isFalse();
            assertThat(MfaPolicy.namesExemptRole(List.of("employee", "mfa-exempt"))).isTrue();
            assertThat(MfaPolicy.isExempt(null)).isFalse();
        }
    }

    @Nested
    class BulkSkipRules {

        private Map<String, Object> base() {
            Map<String, Object> u = new HashMap<>();
            u.put("id", "u");
            u.put("username", "alice");
            u.put("enabled", true);
            u.put("requiredActions", new ArrayList<>());
            return u;
        }

        private Map<String, Object> with(String k, Object v) {
            Map<String, Object> u = base();
            u.put(k, v);
            return u;
        }

        @Test
        void rules() {
            assertThat(MfaPolicy.bulkEnrolmentSkip(base(), false, false)).isEmpty();
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("enabled", false), false, false))
                    .contains(BulkSkipReason.DISABLED);
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("username", "service-account-x"), false, false))
                    .contains(BulkSkipReason.SERVICE_ACCOUNT);
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("serviceAccountClientLink", "c"), false, false))
                    .contains(BulkSkipReason.SERVICE_ACCOUNT);
            assertThat(MfaPolicy.bulkEnrolmentSkip(base(), true, false)).contains(BulkSkipReason.ALREADY_ENROLLED);
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("requiredActions", List.of("CONFIGURE_TOTP")), false, false))
                    .contains(BulkSkipReason.ALREADY_PENDING);
            // a pending action would override the exemption, so exempt people are left alone
            assertThat(MfaPolicy.bulkEnrolmentSkip(base(), false, true)).contains(BulkSkipReason.EXEMPT);
        }

        @Test
        void orderOfChecksAndDefaults() {
            Map<String, Object> noEnabled = base();
            noEnabled.remove("enabled");
            assertThat(MfaPolicy.bulkEnrolmentSkip(noEnabled, false, false)).as("enabled defaults to true").isEmpty();
            // disabled wins over everything else
            assertThat(MfaPolicy.bulkEnrolmentSkip(false, "service-account-x", true, List.of("CONFIGURE_TOTP"),
                    true, true)).contains(BulkSkipReason.DISABLED);
            // exempt wins over enrolled / pending
            assertThat(MfaPolicy.bulkEnrolmentSkip(true, "bob", false, List.of("CONFIGURE_TOTP"), true, true))
                    .contains(BulkSkipReason.EXEMPT);
            // enrolled wins over pending
            assertThat(MfaPolicy.bulkEnrolmentSkip(true, "bob", false, List.of("CONFIGURE_TOTP"), true, false))
                    .contains(BulkSkipReason.ALREADY_ENROLLED);
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("serviceAccountClientLink", ""), false, false)).isEmpty();
            assertThat(MfaPolicy.bulkEnrolmentSkip(with("requiredActions", null), false, false)).isEmpty();
        }

        @Test
        void codesMatchTheReference() {
            assertThat(Stream.of(BulkSkipReason.values()).map(BulkSkipReason::code))
                    .containsExactly("disabled", "service_account", "exempt", "already_enrolled", "already_pending");
        }
    }

    @Nested
    class Helpers {

        @Test
        void attributeReadsStringsAndFirstListElement() {
            assertThat(MfaPolicy.attribute(Map.of("a", "x"), "a")).contains("x");
            assertThat(MfaPolicy.attribute(Map.of("a", List.of("y", "z")), "a")).contains("y");
            assertThat(MfaPolicy.attribute(Map.of("a", List.of()), "a")).isEmpty();
            assertThat(MfaPolicy.attribute(Map.of(), "a")).isEmpty();
            assertThat(MfaPolicy.attribute(null, "a")).isEmpty();
        }

        @ParameterizedTest
        @CsvSource(nullValues = "NULL", value = {"NULL,", "'',", "'  ',", "2,2", "' 1 ',1", "two,"})
        void parseLevel(String raw, Integer expected) {
            assertThat(MfaPolicy.parseLevel(raw)).isEqualTo(Optional.ofNullable(expected));
        }

        @Test
        void constants() {
            assertThat(MfaConstants.ACR_LOA_MAP_VALUE).isEqualTo("{\"1\":1,\"2\":2}");
            assertThat(MfaConstants.ACR_PASSWORD).isEqualTo(String.valueOf(MfaConstants.LOA_PASSWORD));
            assertThat(MfaConstants.ACR_TOTP).isEqualTo(String.valueOf(MfaConstants.LOA_TOTP));
            assertThat(MfaConstants.REQUIRED_ACTIONS).containsExactly("CONFIGURE_TOTP", "CONFIGURE_RECOVERY_AUTHN_CODES");
            assertThat(MfaConstants.EXEMPT_ATTRIBUTES)
                    .containsExactly("mfa_exempt_reason", "mfa_exempt_granted_by", "mfa_exempt_granted_at");
        }

        @Test
        void modeFromValue() {
            assertThat(MfaMode.fromValue(" ALL ")).contains(MfaMode.ALL);
            assertThat(MfaMode.fromValue(null)).isEmpty();
            assertThat(MfaMode.fromValue("x")).isEmpty();
            assertThat(MfaMode.PER_CLIENT).hasToString("per_client");
        }
    }
}
