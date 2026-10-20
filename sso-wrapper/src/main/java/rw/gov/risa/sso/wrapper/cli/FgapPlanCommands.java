package rw.gov.risa.sso.wrapper.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapPlanInputs;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapPlanRenderer;
import rw.gov.risa.sso.wrapper.policy.fgap.PlanInstitution;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;

/**
 * {@code fgap-plan} and {@code fgap-plan-check} (port of {@code scripts/render_fgap_plan.py}): the FGAP v2
 * plan Terraform applies, rendered from {@code templates.json} + {@code institutions.yaml} by
 * {@link FgapPlanRenderer}. Fully offline. The committed {@code configs/generated/fgap-v2-plan.json} uses
 * CRLF line endings; {@code fgap-plan} writes CRLF so it reproduces the file byte for byte, and
 * {@code fgap-plan-check} compares with {@link FgapPlanRenderer#isUpToDate} (CRLF/LF-insensitive).
 */
public final class FgapPlanCommands {

    private FgapPlanCommands() {
    }

    /** The inputs of one render. */
    record Inputs(Path templates, Path registry, Path output, DelegatedPermissionRegistry permissions,
                  List<PlanInstitution> institutions) {
    }

    static Inputs inputs(CliArgs args, String outputOption, DelegationProperties props, java.io.PrintStream out)
            throws Exception {
        Path templates = ConfigFiles.resolve(args.value("templates").orElse(null), props.permissionsConfig());
        if (templates == null) {
            out.println("ERROR: templates not found (" + args.value("templates").orElse(props.permissionsConfig()) + ")");
            return null;
        }
        Path registry = ConfigFiles.resolve(args.value("registry").orElse(null), props.institutionRegistryConfig());
        if (registry == null) {
            out.println("ERROR: registry not found (" + args.value("registry")
                    .orElse(props.institutionRegistryConfig()) + ")");
            return null;
        }
        Path output = args.value(outputOption).map(Path::of).orElseGet(() ->
                ConfigFiles.repoRootOf(templates, FgapPlanRenderer.TEMPLATES_PATH).resolve(FgapPlanRenderer.OUTPUT_PATH));
        DelegatedPermissionRegistry permissions;
        try {
            permissions = DelegatedPermissionsLoader.loadFrom(templates);
        } catch (DelegatedPermissionConfigException e) {
            out.println("INVALID delegated-permission config " + templates + ":\n  " + e.getMessage());
            return null;
        }
        return new Inputs(templates, registry, output, permissions, FgapPlanInputs.fromInstitutionsYaml(registry));
    }

    /** {@code fgap-plan [--stdout] [--output PATH] [--templates PATH] [--registry PATH]}. */
    @Component
    public static class Render extends PrintingCommand {
        private final DelegationProperties props;

        public Render(DelegationProperties props) {
            this.props = props;
        }

        @Override
        public String name() {
            return "fgap-plan";
        }

        @Override
        public String description() {
            return "Render configs/generated/fgap-v2-plan.json from the templates + registry (no Keycloak) "
                    + "[--stdout] [--output PATH] [--templates PATH] [--registry PATH]";
        }

        @Override
        protected Set<String> flags() {
            return Set.of("stdout");
        }

        @Override
        protected Set<String> options() {
            return Set.of("output", "templates", "registry");
        }

        @Override
        protected int execute(CliArgs args) throws Exception {
            Inputs in = inputs(args, "output", props, out);
            if (in == null) {
                return 1;
            }
            String rendered = FgapPlanRenderer.render(in.permissions(), in.institutions(), "\r\n");
            if (args.has("stdout")) {
                out.print(rendered);
                return 0;
            }
            if (in.output().getParent() != null) {
                Files.createDirectories(in.output().getParent());
            }
            Files.writeString(in.output(), rendered, StandardCharsets.UTF_8);
            JsonNode plan = new ObjectMapper().readTree(rendered);
            out.println("Wrote " + in.output() + ": " + plan.path("institutions").size() + " institution(s), "
                    + plan.path("policies").size() + " policy/policies, " + plan.path("permissions").size()
                    + " permission(s).");
            return 0;
        }
    }

    /** {@code fgap-plan-check [--plan PATH] [--templates PATH] [--registry PATH]}: exit 1 when stale or missing. */
    @Component
    public static class Check extends PrintingCommand {
        private final DelegationProperties props;

        public Check(DelegationProperties props) {
            this.props = props;
        }

        @Override
        public String name() {
            return "fgap-plan-check";
        }

        @Override
        public String description() {
            return "Fail (exit 1) if configs/generated/fgap-v2-plan.json is stale [--plan PATH] [--templates PATH] "
                    + "[--registry PATH]";
        }

        @Override
        protected Set<String> flags() {
            return Set.of();
        }

        @Override
        protected Set<String> options() {
            return Set.of("plan", "templates", "registry");
        }

        @Override
        protected int execute(CliArgs args) throws Exception {
            Inputs in = inputs(args, "plan", props, out);
            if (in == null) {
                return 1;
            }
            if (!Files.exists(in.output())) {
                out.println("MISSING: " + in.output() + " has never been generated.\nRun: fgap-plan");
                return 1;
            }
            String committed = Files.readString(in.output(), StandardCharsets.UTF_8);
            if (!FgapPlanRenderer.isUpToDate(committed, in.permissions(), in.institutions())) {
                out.println("STALE: " + in.output() + " does not match the current templates/registry.\nRun: fgap-plan");
                return 1;
            }
            out.println(in.output() + " is up to date.");
            return 0;
        }
    }
}
