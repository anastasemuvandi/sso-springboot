package rw.gov.risa.sso.mfa.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * gorsso/mfa-flow.json and infra/terraform/mfa.tf describe ONE flow. Port of the reference
 * tests/e2e/test_kc26_enable_mfa.py: parses mfa.tf's executions, sub-flows and execution
 * configs into the same (level, kind, key, requirement) tree and compares. A failure means
 * someone changed one and not the other. Change both.
 */
class MfaFlowTerraformConsistencyTest {

    static final Map<String, String> DEFAULTS = Map.of(
            "tostring(var.mfa_loa_max_age_seconds)", String.valueOf(MfaConstants.LOA_MAX_AGE_SECONDS),
            "keycloak_role.mfa_exempt[\"on\"].name", MfaConstants.EXEMPT_ROLE);

    static final Pattern BLOCK = Pattern.compile(
            "^resource \"(keycloak_authentication_[a-z_]+)\" \"([a-z0-9_]+)\" \\{(.*?)^}",
            Pattern.DOTALL | Pattern.MULTILINE);

    record Node(String name, String kind, String key, String req, int prio, String parent) {
    }

    record Row(int level, String kind, String key, String req, int prio, String name) {
    }

    static String tf;
    static JsonNode def;

    @BeforeAll
    static void load() throws Exception {
        Path p = Path.of("..", "infra", "terraform", "mfa.tf");
        assumeTrue(Files.exists(p), "infra/terraform/mfa.tf not present");
        tf = Files.readString(p);
        def = new ObjectMapper().readTree(MfaFlowDefinition.json());
    }

    private static String attr(String body, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE)
                .matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String unquote(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("${local.mfa_flow_alias}", MfaConstants.MFA_FLOW_ALIAS);
    }

    private static String parentName(String body) {
        String parent = attr(body, "parent_flow_alias");
        if (parent.startsWith("keycloak_authentication_flow.")) {
            return "__top__";
        }
        if (parent.equals("each.value.alias")) {
            return attr(body, "for_each").split("\\.")[1];
        }
        Matcher m = Pattern.compile("keycloak_authentication_subflow\\.([a-z0-9_]+)\\[").matcher(parent);
        assertThat(m.find()).as("unrecognised parent_flow_alias: %s", parent).isTrue();
        return m.group(1);
    }

    private static Map<String, String> configMap(String body) {
        Matcher m = Pattern.compile("config = \\{(.*?)}", Pattern.DOTALL).matcher(body);
        assertThat(m.find()).isTrue();
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : m.group(1).split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] kv = line.split("=", 2);
            String k = kv[0].strip().replace("\"", "");
            String raw = kv[1].strip();
            out.put(k, DEFAULTS.getOrDefault(raw, unquote(raw)));
        }
        return out;
    }

    private List<Row> terraformTree(Map<String, Map<String, Object>> configsOut) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(tf);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            String body = m.group(3);
            switch (type) {
                case "keycloak_authentication_execution" -> nodes.put(name, new Node(name, "execution",
                        unquote(attr(body, "authenticator")), unquote(attr(body, "requirement")),
                        Integer.parseInt(attr(body, "priority")), parentName(body)));
                case "keycloak_authentication_subflow" -> nodes.put(name, new Node(name, "flow",
                        unquote(attr(body, "alias")), unquote(attr(body, "requirement")),
                        Integer.parseInt(attr(body, "priority")), parentName(body)));
                case "keycloak_authentication_execution_config" -> {
                    Matcher t = Pattern.compile("keycloak_authentication_execution\\.([a-z0-9_]+)\\[")
                            .matcher(attr(body, "execution_id"));
                    assertThat(t.find()).isTrue();
                    configsOut.put(t.group(1), Map.of("alias", unquote(attr(body, "alias")),
                            "values", configMap(body)));
                }
                default -> {
                }
            }
        }
        List<Row> out = new ArrayList<>();
        walk(nodes, "__top__", 0, out);
        return out;
    }

    private static void walk(Map<String, Node> nodes, String parent, int level, List<Row> out) {
        nodes.values().stream().filter(n -> n.parent().equals(parent))
                .sorted(Comparator.comparingInt(Node::prio))
                .forEach(n -> {
                    out.add(new Row(level, n.kind(), n.key(), n.req(), n.prio(), n.name()));
                    walk(nodes, n.name(), level + 1, out);
                });
    }

    private static List<Row> jsonTree() {
        List<Row> out = new ArrayList<>();
        def.get("executions").forEach(e -> out.add(new Row(e.get("level").asInt(), e.get("kind").asText(),
                e.has("provider_id") ? e.get("provider_id").asText() : e.get("alias").asText(),
                e.get("requirement").asText(), e.get("priority").asInt(), e.get("tf_resource").asText())));
        return out;
    }

    @Test
    void theJsonDescribesTheFlowTerraformBuilds() {
        List<Row> tfTree = terraformTree(new LinkedHashMap<>());
        assertThat(tfTree).as("parsed nothing from mfa.tf — has its layout changed?").isNotEmpty();
        assertThat(jsonTree()).containsExactlyElementsOf(tfTree);
    }

    @Test
    void andConfiguresItTheSameWay() {
        Map<String, Map<String, Object>> tfConfigs = new LinkedHashMap<>();
        terraformTree(tfConfigs);
        Map<String, Map<String, Object>> jsonConfigs = new LinkedHashMap<>();
        ObjectMapper om = new ObjectMapper();
        def.get("executions").forEach(e -> {
            if (e.has("config")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = om.convertValue(e.get("config"), Map.class);
                jsonConfigs.put(e.get("tf_resource").asText(), cfg);
            }
        });
        assertThat(jsonConfigs).isEqualTo(tfConfigs);
    }

    @Test
    void theFlowAliasAndAcrMapMatchTerraform() {
        assertThat(tf).contains("mfa_flow_alias = \"" + MfaConstants.MFA_FLOW_ALIAS + "\"");
        assertThat(tf).contains("name        = \"" + MfaConstants.EXEMPT_ROLE + "\"");
        Path base = Path.of("..", "infra", "terraform", "base.tf");
        if (Files.exists(base)) {
            try {
                assertThat(Files.readString(base)).contains("\"acr.loa.map\" = jsonencode({ \"1\" = 1, \"2\" = 2 })");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
