package rw.gov.risa.sso.wrapper.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.RoleProvisioningPlan;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.platformops.delegated.DelegatedOpsService;

/**
 * {@code delegated-apply [--institution SLUG]... [--dry-run] [--config PATH]}: create each institution's
 * {@code inst:<slug>:*} realm roles from the templates ({@code RoleProvisioningPlan}). Idempotent and
 * additive — never deletes a role. Without {@code --institution}: every configured institution plus every
 * top-level group whose name is a canonical slug. Exit 0 done, 1 invalid config / nothing to do / failure.
 */
@Component
public class DelegatedApplyCommand extends PrintingCommand {

    private final DelegatedOpsService service;
    private final DelegationProperties props;
    private final SsoProperties sso;

    public DelegatedApplyCommand(DelegatedOpsService service, DelegationProperties props, SsoProperties sso) {
        this.service = service;
        this.props = props;
        this.sso = sso;
    }

    @Override
    public String name() {
        return "delegated-apply";
    }

    @Override
    public String description() {
        return "Create the institutions' inst:<slug>:* realm roles [--institution SLUG]... [--dry-run] [--config PATH]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of("dry-run");
    }

    @Override
    protected Set<String> options() {
        return Set.of("institution", "config");
    }

    @Override
    protected int execute(CliArgs args) {
        DelegatedPermissionRegistry registry = loadRegistry(args.value("config").orElse(null), props, out);
        if (registry == null) {
            return 1;
        }
        DelegatedValidateCommand.describe(registry).forEach(out::println);
        boolean dryRun = args.has("dry-run");
        List<String> slugs;
        if (!args.all("institution").isEmpty()) {
            List<String> invalid = args.all("institution").stream().filter(s -> !s.matches("^[a-z0-9-]+$")).toList();
            if (!invalid.isEmpty()) {
                out.println("ERROR: not valid institution slugs (ADR-0001 §5.1): " + invalid);
                return 1;
            }
            slugs = List.copyOf(new TreeSet<>(args.all("institution")));
        } else {
            List<List<String>> found = service.discoverInstitutions(registry);
            slugs = found.get(0);
            if (!found.get(1).isEmpty()) {
                out.println("NOTE: skipping non-canonical group name(s) " + found.get(1) + " — ADR-0001 §5.1 requires "
                        + "lowercase slugs; normalize them before delegating.");
            }
        }
        if (slugs.isEmpty()) {
            out.println("\nNo institutions to provision. Add slugs to assignments.by_institution, create the "
                    + "institution groups, or pass --institution <slug>.");
            return 1;
        }
        out.println("\n" + (dryRun ? "DRY RUN" : "APPLY") + " — realm '" + sso.keycloak().realm() + "', institutions: "
                + slugs);
        DelegatedOpsService.ApplyResult result;
        try {
            result = service.apply(registry, slugs, dryRun);
        } catch (RuntimeException e) {
            out.println("ERROR: " + e.getMessage());
            return 1;
        }
        for (RoleProvisioningPlan.Item item : result.plan().items()) {
            String tag = item.action() == RoleProvisioningPlan.Action.EXISTS ? "exists"
                    : dryRun ? "would create" : "created";
            out.printf("  [%12s] %s   (%s / %s)%n", tag, item.role(), item.institution(), item.template());
        }
        out.println("\n" + (dryRun ? "DRY RUN - nothing was written." : "Done.") + " " + result.created().size()
                + " role(s) created, " + result.plan().unchanged().size() + " already present.");
        if (!dryRun) {
            out.println("Appoint a local admin by assigning them the tier's grant role (and nothing else).");
            out.println("Verify with: delegated-check");
        }
        return 0;
    }

    /** An explicit {@code --config} file, else the configured one; null (reason printed) when unusable. */
    static DelegatedPermissionRegistry loadRegistry(String explicit, DelegationProperties props,
                                                    java.io.PrintStream out) {
        Path path = ConfigFiles.resolve(explicit, props.permissionsConfig());
        if (path == null) {
            out.println("INVALID delegated-permission config: " + (explicit != null ? explicit
                    : props.permissionsConfig()) + " not found.");
            return null;
        }
        try {
            return DelegatedPermissionsLoader.loadFrom(path);
        } catch (DelegatedPermissionConfigException e) {
            out.println("INVALID delegated-permission config " + path + ":\n  " + e.getMessage());
            return null;
        }
    }
}
