package rw.gov.risa.sso.wrapper.policy.fgap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin reader of {@code configs/institutions.yaml} for the plan renderer
 * ({@code render_fgap_plan._institutions}): the active institutions of the shared realm —
 * entries with a slug, {@code status} absent or {@code active}, and {@code separate_realm}
 * not truthy. Validation of the registry itself is the registry module's job.
 */
public final class FgapPlanInputs {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private FgapPlanInputs() {
    }

    public static List<PlanInstitution> fromInstitutionsYaml(Path path) {
        try {
            return fromInstitutionsYaml(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<PlanInstitution> fromInstitutionsYaml(String yaml) {
        JsonNode doc;
        try {
            doc = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<PlanInstitution> out = new ArrayList<>();
        if (doc == null || !doc.path("institutions").isArray()) {
            return out;
        }
        for (JsonNode entry : doc.path("institutions")) {
            String slug = entry.path("slug").isMissingNode() || entry.path("slug").isNull() ? "" : entry.path("slug").asText();
            if (slug.isEmpty()) {
                continue;
            }
            JsonNode status = entry.get("status");
            boolean active = status == null || (status.isTextual() && status.asText().equals("active"));
            if (!active || truthy(entry.get("separate_realm"))) {
                continue;
            }
            List<String> apps = new ArrayList<>();
            for (JsonNode app : iter(entry.get("apps"))) {
                apps.add(app.path("name").asText());
            }
            List<String> departments = new ArrayList<>();
            for (JsonNode d : iter(entry.get("departments"))) {
                departments.add(d.isObject() ? d.path("name").asText() : d.asText());
            }
            out.add(new PlanInstitution(slug, apps, departments));
        }
        return out;
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        return n == null || !n.isArray() ? List.of() : n;
    }

    private static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.doubleValue() != 0.0;
        }
        if (n.isTextual()) {
            return !n.asText().isEmpty();
        }
        return n.size() > 0;
    }
}
