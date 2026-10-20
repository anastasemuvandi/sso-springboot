package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.PermissionTemplate;

/**
 * Renders {@code configs/generated/fgap-v2-plan.json} — a port of
 * {@code scripts/render_fgap_plan.py} (build + serialise). Fully offline: the enumeration
 * inputs come from the registry (departments, apps) and the templates (roles), never from a
 * live realm, so the plan is a pure function of what is committed.
 *
 * <p>Output equals Python's {@code json.dumps(plan, indent=2) + "\n"}. The committed file was
 * written on Windows and therefore has CRLF line endings; pass {@code "\r\n"} to
 * {@link #render(DelegatedPermissionRegistry, List, String)} to reproduce it byte-for-byte, or
 * compare with line endings normalised (as the Python {@code --check} effectively does).
 */
public final class FgapPlanRenderer {

    public static final String TEMPLATES_PATH = "configs/delegated-permissions/templates.json";
    public static final String REGISTRY_PATH = "configs/institutions.yaml";
    public static final String OUTPUT_PATH = "configs/generated/fgap-v2-plan.json";

    public static final String BANNER = "GENERATED FILE — do not edit. Produced by scripts/render_fgap_plan.py from "
            + "configs/delegated-permissions/templates.json and configs/institutions.yaml. "
            + "Run `make fgap-plan` after changing either; CI fails if this is stale.";

    private FgapPlanRenderer() {
    }

    /** The plan as an ordered JSON tree ({@code build()}). */
    public static Map<String, Object> build(DelegatedPermissionRegistry registry, List<PlanInstitution> institutions) {
        List<String> slugs = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<String> clients = new ArrayList<>();
        List<String> subgroups = new ArrayList<>();
        for (PlanInstitution entry : institutions) {
            String slug = entry.slug();
            slugs.add(slug);
            for (PermissionTemplate t : registry.enabledTemplates(slug)) {
                roles.addAll(t.rolesToProvision(slug));
            }
            entry.apps().forEach(app -> clients.add(slug + "-" + app));
            subgroups.add("/" + slug);
            entry.departments().forEach(d -> subgroups.add("/" + slug + "/" + d));
        }

        List<InstitutionPlan> plans = FgapProjection.renderAll(registry, slugs, roles, clients, subgroups,
                SubgroupMode.ENUMERATE);

        List<Object> policies = new ArrayList<>();
        List<Object> permissions = new ArrayList<>();
        for (InstitutionPlan plan : plans) {
            for (RolePolicy p : plan.policies()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("institution", plan.institution());
                m.putAll(p.describe());
                policies.add(m);
            }
            for (ScopePermission p : plan.permissions()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("institution", plan.institution());
                m.putAll(p.describe());
                permissions.add(m);
            }
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("templates", TEMPLATES_PATH);
        source.put("registry", REGISTRY_PATH);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_comment", BANNER);
        out.put("version", 1);
        out.put("source", source);
        out.put("institutions", slugs.stream().sorted().toList());
        out.put("policies", policies);
        out.put("permissions", permissions);
        return out;
    }

    /** The plan file content with {@code \n} line endings (canonical form). */
    public static String render(DelegatedPermissionRegistry registry, List<PlanInstitution> institutions) {
        return render(registry, institutions, "\n");
    }

    /** The plan file content with the given line separator ({@code "\r\n"} reproduces the committed file). */
    public static String render(DelegatedPermissionRegistry registry, List<PlanInstitution> institutions,
                                String lineSeparator) {
        String text = PyJson.dumps(build(registry, institutions), 2) + "\n";
        return "\n".equals(lineSeparator) ? text : text.replace("\n", lineSeparator);
    }

    /** True when {@code committed} equals a fresh render, ignoring CRLF vs LF ({@code --check}). */
    public static boolean isUpToDate(String committed, DelegatedPermissionRegistry registry,
                                     List<PlanInstitution> institutions) {
        return committed.replace("\r\n", "\n").equals(render(registry, institutions));
    }
}
