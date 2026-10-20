package rw.gov.risa.sso.wrapper.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.PermissionTemplate;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;

/**
 * {@code delegated-validate [--config PATH]}: parse and safety-check the delegated-permission templates —
 * every template stays inside its institution's namespace and no allow-pattern can reach a forbidden broad
 * role. No Keycloak, so it is the cheap pre-commit / CI gate. Exit 0 valid, 1 invalid or missing.
 */
@Component
public class DelegatedValidateCommand extends PrintingCommand {

    private final DelegationProperties props;

    public DelegatedValidateCommand(DelegationProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "delegated-validate";
    }

    @Override
    public String description() {
        return "Parse + safety-check the delegated-permission templates (no Keycloak) [--config PATH]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of();
    }

    @Override
    protected Set<String> options() {
        return Set.of("config");
    }

    @Override
    protected int execute(CliArgs args) {
        String explicit = args.value("config").orElse(null);
        Path path = ConfigFiles.resolve(explicit, props.permissionsConfig());
        if (path == null) {
            out.println("INVALID delegated-permission config: " + (explicit != null ? explicit
                    : props.permissionsConfig()) + " not found.");
            return 1;
        }
        DelegatedPermissionRegistry registry;
        try {
            registry = DelegatedPermissionsLoader.loadFrom(path);
        } catch (DelegatedPermissionConfigException e) {
            out.println("INVALID delegated-permission config " + path + ":\n  " + e.getMessage());
            return 1;
        }
        describe(registry).forEach(out::println);
        out.println();
        out.println("Config is valid: every template stays inside its institution's");
        out.println("namespace and no allow-pattern can reach a forbidden broad role.");
        return 0;
    }

    /** The reference's {@code _describe_config} lines. */
    public static List<String> describe(DelegatedPermissionRegistry registry) {
        List<String> lines = new ArrayList<>();
        lines.add("Config: " + registry.source());
        lines.add("  guardrail: forbidden realm roles " + registry.guardrail().forbiddenRealmRoles().stream()
                .sorted().toList());
        for (Map.Entry<String, List<String>> e : new TreeMap<>(registry.guardrail().forbiddenClientRoles()).entrySet()) {
            lines.add("             forbidden client roles " + e.getKey() + ": " + e.getValue());
        }
        lines.add("  default tiers: " + registry.defaultTemplates());
        new TreeMap<>(registry.byInstitution()).forEach((slug, names) -> lines.add("  " + slug + ": " + names));
        lines.add("  templates (" + registry.templates().size() + "):");
        for (PermissionTemplate t : registry.templates().values()) {
            lines.add("    - " + t.name() + "  grant=" + t.grantRole());
            lines.add("        capabilities: " + t.capabilities().stream().map(Capability::wire).sorted().toList());
            lines.add("        members:      " + t.group() + " (subgroups: " + t.includeSubgroups() + ")");
            lines.add("        roles:        allow=" + t.roleAllow() + " deny=" + t.roleDeny());
            lines.add("        clients:      allow=" + t.clientAllow() + " deny=" + t.clientDeny());
        }
        return lines;
    }
}
