package rw.gov.risa.sso.wrapper.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapProjection;
import rw.gov.risa.sso.wrapper.policy.fgap.InstitutionPlan;
import rw.gov.risa.sso.wrapper.policy.fgap.SubgroupMode;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminSession;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.FgapApplier;

/**
 * {@code fgap-apply [--institution SLUG]... [--plan PATH] [--dry-run] [--subgroups enumerate|propagate]
 * [--config PATH] [--url URL] [--realm REALM] [--admin-user U --admin-password P]}: apply the FGAP v2 plan to
 * a realm through the Admin API ({@link FgapApplier}) — for tests and offline deployments; Terraform is the
 * production path.
 *
 * <p>Default: the plan is projected from the templates against what the realm holds (its roles, clients and
 * group paths) for the named institutions — or every registry institution whose group exists. With
 * {@code --plan}: the rendered file (e.g. {@code configs/generated/fgap-v2-plan.json}) is applied as is,
 * filtered to {@code --institution} (plus the shared map-roles object) when given. Exit 0 applied cleanly,
 * 1 when something was skipped or unresolved, 64 usage.
 */
@Component
public class FgapApplyCommand extends PrintingCommand {

    private final DelegationProperties props;
    private final InstitutionRegistryHolder registry;
    private final SsoProperties sso;
    private final KeycloakAdminFactory factory;

    public FgapApplyCommand(DelegationProperties props, InstitutionRegistryHolder registry, SsoProperties sso,
                            KeycloakAdminFactory factory) {
        this.props = props;
        this.registry = registry;
        this.sso = sso;
        this.factory = factory;
    }

    @Override
    public String name() {
        return "fgap-apply";
    }

    @Override
    public String description() {
        return "Apply the FGAP v2 plan to the realm via the Admin API (tests/offline; Terraform in prod) "
                + "[--institution SLUG]... [--plan PATH] [--dry-run] [--subgroups enumerate|propagate] "
                + "[--admin-user U --admin-password P] [--url URL] [--realm REALM]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of("dry-run");
    }

    @Override
    protected Set<String> options() {
        Set<String> o = new HashSet<>(AdminIdentityOptions.OPTIONS);
        o.addAll(Set.of("institution", "plan", "subgroups", "config"));
        return o;
    }

    @Override
    protected int execute(CliArgs args) throws Exception {
        SubgroupMode mode = switch (args.value("subgroups").orElse("enumerate")) {
            case "enumerate" -> SubgroupMode.ENUMERATE;
            case "propagate" -> SubgroupMode.PROPAGATE;
            default -> null;
        };
        if (mode == null) {
            out.println("ERROR: --subgroups must be enumerate or propagate");
            return USAGE;
        }
        boolean dryRun = args.has("dry-run");
        try (AdminSession session = AdminIdentityOptions.open(args, sso, factory)) {
            out.println("Realm '" + session.rest().realm() + "' at " + session.rest().baseUrl() + " as "
                    + session.identity());
            List<InstitutionPlan> plans;
            if (args.value("plan").isPresent()) {
                Path file = Path.of(args.value("plan").get());
                plans = FgapApplier.fromPlanFile(Files.readString(file, StandardCharsets.UTF_8));
                List<String> only = args.all("institution");
                if (!only.isEmpty()) {
                    plans = plans.stream().filter(p -> only.contains(p.institution())
                            || FgapProjection.SHARED.equals(p.institution())).toList();
                }
                out.println("Plan file " + file + ": " + plans.size() + " plan(s)");
            } else {
                DelegatedPermissionRegistry templates = DelegatedApplyCommand.loadRegistry(
                        args.value("config").orElse(null), props, out);
                if (templates == null) {
                    return 1;
                }
                FgapApplier.Inventory inventory = FgapApplier.inventory(session.rest());
                List<String> slugs = new ArrayList<>(args.all("institution"));
                if (slugs.isEmpty()) {
                    Set<String> paths = new HashSet<>(inventory.groupPaths());
                    for (String slug : new TreeSet<>(registry.knownSlugs())) {
                        if (paths.contains("/" + slug)) {
                            slugs.add(slug);
                        }
                    }
                }
                if (slugs.isEmpty()) {
                    out.println("No institutions to project: pass --institution <slug>, or onboard one first.");
                    return 1;
                }
                out.println("Projecting " + slugs + " against the realm (subgroups: " + mode.wire() + ")");
                plans = FgapApplier.livePlans(templates, inventory, slugs, mode);
            }
            FgapApplier.Report report = new FgapApplier(session.rest(), dryRun).apply(plans);
            report.lines().forEach(out::println);
            if (!report.problems().isEmpty()) {
                out.println("\n" + report.problems().size() + " problem(s):");
                report.problems().forEach(p -> out.println("  - " + p));
                return 1;
            }
            if (!dryRun) {
                out.println("Verify with: verify --institution <mine> --institution <theirs>");
            }
            return 0;
        }
    }
}
