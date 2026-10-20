package rw.gov.risa.sso.wrapper.service.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mfa.MfaFlowInstaller.FlowRow;

/**
 * The installer's flow (what {@link MfaFlowInstaller} builds, node by node) is the flow
 * {@code infra/terraform/mfa.tf} builds: same tree of (level, exec|flow, provider|alias, requirement)
 * in priority order, and the same execution configs. Parses mfa.tf like the reference test.
 */
class MfaFlowInstallerTerraformTest {

    static final Pattern BLOCK = Pattern.compile(
            "^resource \"(keycloak_authentication_[a-z_]+)\" \"([a-z0-9_]+)\" \\{(.*?)^}", Pattern.DOTALL | Pattern.MULTILINE);

    record Node(String name, String kind, String key, String req, int prio, String parent) {
    }

    final MfaFlowInstaller installer = new MfaFlowInstaller(mock(KeycloakExecutors.class), new KeycloakDirectory());

    static String tf() throws Exception {
        Path here = Path.of("").toAbsolutePath();
        for (Path base : List.of(here, here.getParent())) {
            Path p = base.resolve("infra/terraform/mfa.tf");
            if (Files.exists(p)) {
                return Files.readString(p);
            }
        }
        assumeTrue(false, "infra/terraform/mfa.tf not present");
        return null;
    }

    static String attr(String body, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE).matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }

    static String unquote(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("${local.mfa_flow_alias}", MfaConstants.MFA_FLOW_ALIAS);
    }

    static String parent(String body) {
        String p = attr(body, "parent_flow_alias");
        if (p.startsWith("keycloak_authentication_flow.")) {
            return "__top__";
        }
        if (p.equals("each.value.alias")) {
            return attr(body, "for_each").split("\\.")[1];
        }
        Matcher m = Pattern.compile("keycloak_authentication_subflow\\.([a-z0-9_]+)\\[").matcher(p);
        assertThat(m.find()).as("unrecognised parent %s", p).isTrue();
        return m.group(1);
    }

    @Test
    void installerFlowMatchesTerraform() throws Exception {
        String tf = tf();
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, String> configAliasByExec = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(tf);
        while (m.find()) {
            String type = m.group(1);
            String name = m.group(2);
            String body = m.group(3);
            if (type.equals("keycloak_authentication_execution")) {
                nodes.put(name, new Node(name, "exec", unquote(attr(body, "authenticator")),
                        unquote(attr(body, "requirement")), Integer.parseInt(attr(body, "priority")), parent(body)));
            } else if (type.equals("keycloak_authentication_subflow")) {
                nodes.put(name, new Node(name, "flow", unquote(attr(body, "alias")),
                        unquote(attr(body, "requirement")), Integer.parseInt(attr(body, "priority")), parent(body)));
            } else if (type.equals("keycloak_authentication_execution_config")) {
                Matcher t = Pattern.compile("keycloak_authentication_execution\\.([a-z0-9_]+)\\[")
                        .matcher(attr(body, "execution_id"));
                assertThat(t.find()).isTrue();
                configAliasByExec.put(t.group(1), unquote(attr(body, "alias")));
            }
        }
        List<FlowRow> terraform = new ArrayList<>();
        walk(nodes, "__top__", 0, terraform);
        assertThat(terraform).as("parsed nothing from mfa.tf").isNotEmpty();

        String orgRequirement = terraform.stream().filter(r -> r.key().equals(MfaConstants.MFA_FLOW_ALIAS + " Organization"))
                .map(FlowRow::requirement).findFirst().orElse(null);
        assertThat(installer.rows(orgRequirement)).containsExactlyElementsOf(terraform);

        List<String> installerConfigs = installer.plan(orgRequirement).stream()
                .map(MfaFlowInstaller.FlowNode::configAlias).filter(a -> a != null).sorted().toList();
        assertThat(installerConfigs).containsExactlyElementsOf(configAliasByExec.values().stream().sorted().toList());
    }

    @Test
    void withoutTheOrganizationFeatureTheSubflowIsOmitted() {
        assertThat(installer.rows(null)).noneMatch(r -> r.key().contains("Organization"));
        assertThat(installer.rows(null).get(0)).isEqualTo(new FlowRow(0, "exec", "gorsso-mfa-step-up", "REQUIRED"));
    }

    private static void walk(Map<String, Node> nodes, String parent, int level, List<FlowRow> out) {
        nodes.values().stream().filter(n -> n.parent().equals(parent)).sorted(Comparator.comparingInt(Node::prio))
                .forEach(n -> {
                    out.add(new FlowRow(level, n.kind(), n.key(), n.req()));
                    walk(nodes, n.name(), level + 1, out);
                });
    }
}
