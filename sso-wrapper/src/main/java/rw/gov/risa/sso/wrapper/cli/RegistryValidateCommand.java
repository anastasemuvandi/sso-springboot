package rw.gov.risa.sso.wrapper.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistryLoader;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryFindings;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryValidator;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;

/**
 * {@code registry-validate [--registry PATH] [--templates PATH] [--strict]} (port of
 * {@code scripts/validate_institution_registry.py}): slug rules, prefix collisions, ippis_code uniqueness,
 * client-id collisions and tiers, via {@link RegistryValidator}. No Keycloak. Exit 0 clean, 1 on errors (or
 * on warnings with {@code --strict}).
 */
@Component
public class RegistryValidateCommand extends PrintingCommand {

    private final DelegationProperties props;

    public RegistryValidateCommand(DelegationProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "registry-validate";
    }

    @Override
    public String description() {
        return "Check configs/institutions.yaml against the templates (no Keycloak) [--registry PATH] "
                + "[--templates PATH] [--strict]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of("strict");
    }

    @Override
    protected Set<String> options() {
        return Set.of("registry", "templates");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected int execute(CliArgs args) throws Exception {
        Path registry = ConfigFiles.resolve(args.value("registry").orElse(null), props.institutionRegistryConfig());
        Path templates = ConfigFiles.resolve(args.value("templates").orElse(null), props.permissionsConfig());
        if (registry == null || templates == null) {
            out.println("ERROR: " + (registry == null ? args.value("registry").orElse(props.institutionRegistryConfig())
                    : args.value("templates").orElse(props.permissionsConfig())) + " not found.");
            return 1;
        }
        Object doc = InstitutionRegistryLoader.readRaw(registry);
        Map<String, Object> templatesDoc = new ObjectMapper().readValue(templates.toFile(), Map.class);
        RegistryFindings f = RegistryValidator.validate(doc, templatesDoc);
        boolean strict = args.has("strict");

        List<?> entries = doc instanceof Map<?, ?> m && m.get("institutions") instanceof List<?> l ? l : List.of();
        List<Map<?, ?>> active = entries.stream().filter(e -> e instanceof Map<?, ?>).<Map<?, ?>>map(e -> (Map<?, ?>) e)
                .filter(e -> "active".equals(String.valueOf(e.get("status") == null ? "active" : e.get("status"))))
                .toList();
        int clients = active.stream().mapToInt(e -> e.get("apps") instanceof List<?> a ? a.size() : 0).sum();
        Object tiers = templatesDoc.get("templates");
        out.println("Registry:  " + registry);
        out.println("Templates: " + templates);
        out.println("  " + entries.size() + " institution(s), " + active.size() + " active, " + clients
                + " client app(s)");
        out.println("  tiers available: " + (tiers instanceof Map<?, ?> t
                ? t.keySet().stream().map(String::valueOf).sorted().toList() : List.of()));
        if (!f.warnings().isEmpty()) {
            out.println("\nWarnings (" + f.warnings().size() + "):");
            f.warnings().forEach(w -> out.println("  - " + w));
        }
        if (!f.errors().isEmpty()) {
            out.println("\nErrors (" + f.errors().size() + "):");
            f.errors().forEach(e -> out.println("  - " + e));
            out.println("\nResult: FINDINGS PRESENT");
            return 1;
        }
        if (strict && !f.warnings().isEmpty()) {
            out.println("\nResult: WARNINGS PRESENT (--strict)");
            return 1;
        }
        out.println("\nResult: CLEAN");
        return 0;
    }
}
