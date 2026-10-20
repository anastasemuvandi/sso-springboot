package rw.gov.risa.sso.wrapper.cli;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedAudit;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.platformops.delegated.DelegatedOpsService;

/**
 * {@code delegated-check [--institution SLUG]... [--config PATH]}: audit Keycloak against the templates —
 * guardrail violations (a delegated admin who also holds a broad admin role, read through COMPOSITES),
 * unmanaged grants (a grant role for a withheld tier, usually console-made) and missing roles. Exit 0 clean,
 * 1 on ANY finding (the CI / deployment gate), 1 on an unusable config.
 */
@Component
public class DelegatedCheckCommand extends PrintingCommand {

    private final DelegatedOpsService service;
    private final DelegationProperties props;

    public DelegatedCheckCommand(DelegatedOpsService service, DelegationProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public String name() {
        return "delegated-check";
    }

    @Override
    public String description() {
        return "Audit Keycloak for guardrail violations / drift; non-zero on findings [--institution SLUG]... "
                + "[--config PATH]";
    }

    @Override
    protected Set<String> flags() {
        return Set.of();
    }

    @Override
    protected Set<String> options() {
        return Set.of("institution", "config");
    }

    @Override
    protected int execute(CliArgs args) {
        DelegatedPermissionRegistry registry = DelegatedApplyCommand.loadRegistry(args.value("config").orElse(null),
                props, out);
        if (registry == null) {
            return 1;
        }
        DelegatedAudit.Report report = service.audit(registry, args.all("institution"));
        print(service.describeAudit(report));
        return report.clean() ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    void print(Map<String, Object> r) {
        out.println("\nAudit of realm '" + r.get("realm") + "'  (config: " + r.get("source") + ")");
        out.println("  request-time guardrail enforcement: " + (Boolean.TRUE.equals(r.get("guardrail_enforced")) ? "ON"
                : "OFF (DELEGATED_GUARDRAIL_ENFORCE=false)"));
        for (Map<String, Object> inst : (List<Map<String, Object>>) r.get("institutions")) {
            out.println("\n  " + inst.get("institution") + (Boolean.TRUE.equals(inst.get("configured")) ? ""
                    : "  (using default tiers)"));
            for (Map<String, Object> tier : (List<Map<String, Object>>) inst.get("tiers")) {
                out.printf("    %-22s %s%s%n", tier.get("template"), tier.get("grant_role"),
                        Boolean.TRUE.equals(tier.get("grant_role_exists")) ? "" : "  [ROLE MISSING]");
                List<Map<String, Object>> holders = (List<Map<String, Object>>) tier.get("holders");
                if (holders.isEmpty()) {
                    out.println("        (no holders)");
                }
                for (Map<String, Object> h : holders) {
                    out.println("        " + (Boolean.TRUE.equals(h.get("guardrail_ok")) ? "OK " : "!! ")
                            + h.get("username"));
                }
            }
        }
        List<Map<String, Object>> violations = (List<Map<String, Object>>) r.get("guardrail_violations");
        if (!violations.isEmpty()) {
            out.println("\nGUARDRAIL VIOLATIONS — an institution admin must never hold a broad");
            out.println("admin role; it bypasses every per-institution check.");
            for (Map<String, Object> v : violations) {
                String held = String.join(", ", ((List<Map<String, Object>>) v.get("forbidden")).stream()
                        .map(f -> String.valueOf(f.get("detail"))).toList());
                out.println("  - " + v.get("username") + " (" + v.get("institution") + ", via "
                        + String.join(", ", (List<String>) v.get("grant_roles")) + ") holds: " + held);
                if (Boolean.TRUE.equals(v.get("is_platform_admin"))) {
                    out.println("      ^ this account is also a platform admin. Request-time exempts it,");
                    out.println("        so the fix is to separate the platform and local-admin accounts.");
                }
            }
        }
        List<Map<String, Object>> unmanaged = (List<Map<String, Object>>) r.get("unmanaged_grants");
        if (!unmanaged.isEmpty()) {
            out.println("\nUNMANAGED GRANTS — grant roles that exist for a tier the config");
            out.println("withholds (typically created by hand in the admin console).");
            unmanaged.forEach(u -> out.println("  - " + u.get("role") + ": " + u.get("reason")));
        }
        List<Map<String, Object>> missing = (List<Map<String, Object>>) r.get("missing_roles");
        if (!missing.isEmpty()) {
            out.println("\nMISSING ROLES — the config expects these but Keycloak lacks them, so");
            out.println("the tier cannot be appointed. Fix with delegated-apply.");
            missing.forEach(m -> out.println("  - " + m.get("role") + "  (" + m.get("institution") + " / "
                    + m.get("template") + ")"));
        }
        out.println("\nResult: " + (Boolean.TRUE.equals(r.get("clean")) ? "CLEAN" : "FINDINGS PRESENT"));
    }
}
