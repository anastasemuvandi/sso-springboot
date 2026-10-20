package rw.gov.risa.sso.mfa.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural invariants of gorsso/mfa-flow.json, and its agreement with the policy constants. */
class MfaFlowDefinitionTest {

    static JsonNode def;

    @BeforeAll
    static void load() throws Exception {
        def = new ObjectMapper().readTree(MfaFlowDefinition.json());
    }

    private static List<JsonNode> executions() {
        List<JsonNode> out = new ArrayList<>();
        def.get("executions").forEach(out::add);
        return out;
    }

    @Test
    void headerMatchesTheConstants() {
        assertThat(def.at("/flow/alias").asText()).isEqualTo(MfaConstants.MFA_FLOW_ALIAS);
        assertThat(def.at("/flow/provider_id").asText()).isEqualTo("basic-flow");
        assertThat(def.at("/realm_attributes/acr.loa.map").asText()).isEqualTo(MfaConstants.ACR_LOA_MAP_VALUE);
        assertThat(def.at("/exempt_role/name").asText()).isEqualTo(MfaConstants.EXEMPT_ROLE);
        assertThat(def.at("/exempt_role/description").asText()).isEqualTo(MfaConstants.EXEMPT_ROLE_DESCRIPTION);
        assertThat(def.at("/loa_max_age_seconds").asInt()).isEqualTo(MfaConstants.LOA_MAX_AGE_SECONDS);
        assertThat(def.at("/initial_mode_if_unset").asText()).isEqualTo(MfaMode.OFF.value());
        assertThat(def.at("/events/listener").asText()).isEqualTo(MfaConstants.PROVIDER_AUDIT_LISTENER);
        List<String> types = new ArrayList<>();
        def.at("/events/enabled_event_types").forEach(n -> types.add(n.asText()));
        assertThat(types).isEqualTo(MfaConstants.EVENT_TYPES);
        List<String> actions = new ArrayList<>();
        def.at("/required_actions").forEach(n -> actions.add(n.get("alias").asText()));
        assertThat(actions).isEqualTo(MfaConstants.REQUIRED_ACTIONS);
        List<String> authenticators = new ArrayList<>();
        def.at("/required_server_providers/authenticator").forEach(n -> authenticators.add(n.asText()));
        assertThat(authenticators).containsAll(MfaConstants.REQUIRED_AUTHENTICATORS)
                .contains(MfaConstants.RECOVERY_CODE_FORM);
    }

    @Test
    void indicesAreSequentialAndParentsPrecedeChildrenAtTheRightLevel() {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(MfaConstants.MFA_FLOW_ALIAS);
        Set<String> aliases = new HashSet<>();
        int i = 0;
        for (JsonNode e : executions()) {
            assertThat(e.get("index").asInt()).isEqualTo(i++);
            int level = e.get("level").asInt();
            // depth-first order: the parent is the flow on the stack at this level
            while (stack.size() > level + 1) {
                stack.pop();
            }
            assertThat(e.get("parent").asText()).as("parent of #%d", i - 1).isEqualTo(stack.peek());
            assertThat(e.get("requirement").asText()).isIn("REQUIRED", "ALTERNATIVE", "CONDITIONAL", "DISABLED");
            assertThat(e.get("tf_resource").asText()).isNotBlank();
            if ("flow".equals(e.get("kind").asText())) {
                String alias = e.get("alias").asText();
                assertThat(alias).startsWith(MfaConstants.MFA_FLOW_ALIAS + " ");
                assertThat(aliases.add(alias)).as("unique sub-flow alias %s", alias).isTrue();
                assertThat(e.get("flow_provider_id").asText()).isEqualTo("basic-flow");
                stack.push(alias);
            } else {
                assertThat(e.get("kind").asText()).isEqualTo("execution");
                assertThat(e.get("provider_id").asText()).isNotBlank();
            }
        }
    }

    @Test
    void theShapeTheProvidersRelyOn() {
        List<JsonNode> ex = executions();
        // step-up first, REQUIRED, beside a REQUIRED `main` wrapper sub-flow
        assertThat(ex.get(0).get("provider_id").asText()).isEqualTo(MfaConstants.PROVIDER_STEP_UP);
        assertThat(ex.get(1).get("alias").asText()).isEqualTo("browser-totp main");
        assertThat(ex.stream().filter(e -> e.get("level").asInt() == 0)).hasSize(2);

        JsonNode exempt = find(ex, "not_exempt");
        assertThat(exempt.at("/config/values/condUserRole").asText()).isEqualTo(MfaConstants.EXEMPT_ROLE);
        assertThat(exempt.at("/config/values/negate").asText()).isEqualTo("true");
        JsonNode sw = find(ex, "switch");
        assertThat(sw.get("index").asInt()).as("exemption is checked before the switch")
                .isGreaterThan(exempt.get("index").asInt());
        assertThat(sw.get("provider_id").asText()).isEqualTo(MfaConstants.PROVIDER_REALM_ATTRIBUTE_CONDITION);
        assertThat(sw.at("/config/values/attribute").asText()).isEqualTo(MfaConstants.LEGACY_ATTRIBUTE);
        assertThat(sw.at("/config/values/loa").asText()).isEqualTo(MfaConstants.ACR_TOTP);
        assertThat(find(ex, "loa1").at("/config/values/loa-condition-level").asText()).isEqualTo(MfaConstants.ACR_PASSWORD);
        assertThat(find(ex, "loa2_declaration").get("requirement").asText()).isEqualTo("DISABLED");
        assertThat(find(ex, "recovery_reenrol").get("provider_id").asText())
                .isEqualTo(MfaConstants.PROVIDER_RECOVERY_REENROL);
        assertThat(find(ex, "enrol_no_otp").at("/config/values/credential_type").asText())
                .isEqualTo(MfaConstants.OTP_CREDENTIAL_TYPE);
        assertThat(find(ex, "enrol_no_recovery_codes").at("/config/values/credential_type").asText())
                .isEqualTo(MfaConstants.RECOVERY_CODES_CREDENTIAL_TYPE);
        assertThat(ex.stream().filter(e -> e.has("optional")).map(e -> e.get("tf_resource").asText()))
                .containsExactly("organization", "organization_conditional", "organization_user_configured",
                        "organization_identity_first");
    }

    private static JsonNode find(List<JsonNode> ex, String tf) {
        return ex.stream().filter(e -> tf.equals(e.get("tf_resource").asText())).findFirst().orElseThrow();
    }
}
