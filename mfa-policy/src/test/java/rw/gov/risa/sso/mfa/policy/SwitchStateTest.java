package rw.gov.risa.sso.mfa.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of test_mfa_totp.py TestSwitchReading.test_effective_* and TestPerClient.test_applies_to_needs_the_flow. */
class SwitchStateTest {

    @Test
    void effectiveNeedsTheFlowAsWellAsTheAttribute() {
        SwitchState onButNoFlow = SwitchState.fromRealm(Map.of("browserFlow", "browser", "attributes", Map.of()));
        assertThat(onButNoFlow.attributeOn()).isTrue();
        assertThat(onButNoFlow.effective()).isFalse();

        SwitchState on = SwitchState.fromRealm(Map.of("browserFlow", "browser-totp",
                "attributes", Map.of("mfa_totp_enforced", "true")));
        SwitchState off = SwitchState.fromRealm(Map.of("browserFlow", "browser-totp",
                "attributes", Map.of("mfa_totp_enforced", "false")));
        assertThat(on.effective()).isTrue();
        assertThat(off.effective()).isFalse();
    }

    @Test
    void appliesToNeedsTheFlow() {
        assertThat(new SwitchState(null, "browser", "all").appliesTo(null)).isFalse();
        assertThat(new SwitchState(null, "browser-totp", "all").appliesTo(null)).isTrue();
    }

    @ParameterizedTest(name = "flow={0} mode={1} legacy={2} client={3} -> effective={4} applies={5}")
    @CsvSource(nullValues = "NULL", value = {
            "browser-totp, per_client, NULL,  NULL,  true,  true",
            "browser-totp, per_client, NULL,  false, true,  false",
            "browser-totp, off,        true,  NULL,  false, false",
            "browser-totp, NULL,       NULL,  false, true,  true",
            "browser-totp, NULL,       false, NULL,  false, false",
            "browser,      all,        NULL,  NULL,  false, false",
            "NULL,         all,        NULL,  NULL,  false, false",
    })
    void table(String flow, String mode, String legacy, String client, boolean effective, boolean applies) {
        SwitchState s = new SwitchState(legacy, flow, mode);
        assertThat(s.effective()).isEqualTo(effective);
        assertThat(s.appliesTo(client)).isEqualTo(applies);
    }

    @Test
    void fromRealmToleratesMissingParts() {
        assertThat(SwitchState.fromRealm(null)).isEqualTo(new SwitchState(null, null, null));
        Map<String, Object> realm = new HashMap<>();
        realm.put("attributes", null);
        assertThat(SwitchState.fromRealm(realm).mode()).isEqualTo(MfaMode.ALL);
    }

    @Test
    void describeMatchesTheReferenceStateFields() {
        // test_state_reports_mode_effective_and_exempt_count (the exempt count is the caller's)
        Map<String, Object> d = SwitchState.fromRealm(Map.of("browserFlow", "browser-totp", "attributes", Map.of()))
                .describe();
        assertThat(d).containsEntry("mode", "all").containsEntry("enforced", true)
                .containsEntry("effective", true).containsEntry("raw_mode", null)
                .containsEntry("flow_ready", true).containsEntry("raw", null)
                .containsEntry("browser_flow", "browser-totp");
        assertThat(d.keySet()).containsExactly("mode", "raw_mode", "enforced", "effective", "raw",
                "browser_flow", "flow_ready");
    }

    @Test
    void aLegacyRealmReadsAsItsOldSwitch() {
        assertThat(SwitchState.fromRealm(Map.of("attributes", Map.of("mfa_totp_enforced", "false"))).mode())
                .isEqualTo(MfaMode.OFF);
    }
}
