package rw.gov.risa.sso.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three files the seeder reads, all of them the same files Terraform and the wrapper read:
 * {@code configs/institutions.yaml} (which institutions exist), {@code
 * configs/delegated-permissions/templates.json} (which {@code inst:<slug>:*} roles a tier needs) and
 * {@code configs/generated/fgap-v2-plan.json} (the Keycloak-side FGAP v2 permissions). Nothing is
 * hard-coded here: roles are RENDERED from the templates, exactly like {@code
 * PermissionTemplate.rolesToProvision} in the wrapper, so the seeded realm cannot drift from the
 * policy the wrapper enforces.
 */
final class Sources {

    record App(String name, String displayName, List<String> roles) {
        String clientId(String slug) {
            return slug + "-" + name;
        }
    }

    record Institution(String slug, String displayName, String status, List<String> departments, List<App> apps) {
    }

    /** One tier template, already rendered for a slug. */
    record Tier(String name, String displayName, String grantRole, List<String> provisionRoles) {
    }

    final Map<String, Institution> institutions;
    final JsonNode templates;
    final JsonNode fgapPlan; // MissingNode when the plan file is absent

    private Sources(Map<String, Institution> institutions, JsonNode templates, JsonNode fgapPlan) {
        this.institutions = institutions;
        this.templates = templates;
        this.fgapPlan = fgapPlan;
    }

    static Sources load(ObjectMapper json, Path configDir) throws IOException {
        Path registry = configDir.resolve("institutions.yaml");
        Path templates = configDir.resolve("delegated-permissions/templates.json");
        Path plan = configDir.resolve("generated/fgap-v2-plan.json");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode reg = yaml.readTree(Files.readString(registry));
        JsonNode tpl = json.readTree(Files.readString(templates));
        JsonNode fgap = Files.exists(plan) ? json.readTree(Files.readString(plan))
                : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        return new Sources(parseRegistry(reg), tpl, fgap);
    }

    static Sources of(JsonNode registry, JsonNode templates, JsonNode plan) {
        return new Sources(parseRegistry(registry), templates, plan);
    }

    static Map<String, Institution> parseRegistry(JsonNode reg) {
        Map<String, Institution> out = new LinkedHashMap<>();
        for (JsonNode i : reg.path("institutions")) {
            String slug = i.path("slug").asText("");
            if (slug.isEmpty()) {
                continue;
            }
            List<String> departments = new ArrayList<>();
            for (JsonNode d : i.path("departments")) {
                departments.add(d.isObject() ? d.path("name").asText() : d.asText());
            }
            List<App> apps = new ArrayList<>();
            for (JsonNode a : i.path("apps")) {
                List<String> roles = new ArrayList<>();
                a.path("roles").forEach(r -> roles.add(r.asText()));
                apps.add(new App(a.path("name").asText(), a.path("display_name").asText(null), roles));
            }
            out.put(slug, new Institution(slug, i.path("display_name").asText(slug),
                    i.path("status").asText("active"), departments, apps));
        }
        return out;
    }

    /** Tier names enabled for {@code slug}: {@code assignments.by_institution[slug]}, else {@code default}. */
    List<String> tiersFor(String slug) {
        JsonNode assignments = templates.path("assignments");
        JsonNode chosen = assignments.path("by_institution").path(slug);
        if (chosen.isMissingNode() || chosen.isNull()) {
            chosen = assignments.path("default");
        }
        List<String> out = new ArrayList<>();
        chosen.forEach(t -> out.add(t.asText()));
        return out;
    }

    List<Tier> tiers(String slug) {
        List<Tier> out = new ArrayList<>();
        for (String name : tiersFor(slug)) {
            JsonNode t = templates.path("templates").path(name);
            if (t.isMissingNode()) {
                throw new IllegalStateException("tier '" + name + "' (assigned to " + slug
                        + ") is not defined in templates.json");
            }
            List<String> provision = new ArrayList<>();
            t.path("provision").path("roles").forEach(r -> provision.add(r.asText()));
            out.add(new Tier(name, t.path("display_name").asText(name),
                    render(t.path("grant_role").asText(), slug), provision));
        }
        return out;
    }

    /** {@code grant_role} first, then {@code inst:<slug>:<provision role>}, de-duplicated, in order. */
    static List<String> rolesToProvision(Tier tier, String slug) {
        Set<String> out = new LinkedHashSet<>();
        out.add(tier.grantRole());
        for (String shortName : tier.provisionRoles()) {
            out.add("inst:" + slug + ":" + shortName);
        }
        return List.copyOf(out);
    }

    /** Every realm role the enabled templates require for {@code slug}, mapped to a description. */
    Map<String, String> rolesFor(String slug) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Tier tier : tiers(slug)) {
            for (String role : rolesToProvision(tier, slug)) {
                out.putIfAbsent(role, tier.displayName() + " (" + slug + ")");
            }
        }
        return out;
    }

    /** The grant role of one tier, e.g. {@code institution-admin} → {@code inst:minagri:admin}. */
    String grantRole(String tierName, String slug) {
        JsonNode t = templates.path("templates").path(tierName);
        if (t.isMissingNode()) {
            throw new IllegalStateException("tier '" + tierName + "' is not defined in templates.json");
        }
        return render(t.path("grant_role").asText(), slug);
    }

    static String render(String pattern, String slug) {
        return pattern.replace("{slug}", slug);
    }
}
