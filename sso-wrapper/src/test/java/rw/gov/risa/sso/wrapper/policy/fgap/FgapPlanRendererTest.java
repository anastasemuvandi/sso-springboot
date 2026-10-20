package rw.gov.risa.sso.wrapper.policy.fgap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.TestRepo;

class FgapPlanRendererTest {

    private static DelegatedPermissionRegistry registry() {
        return DelegatedPermissionsLoader.loadFrom(TestRepo.path(FgapPlanRenderer.TEMPLATES_PATH));
    }

    private static List<PlanInstitution> institutions() {
        return FgapPlanInputs.fromInstitutionsYaml(TestRepo.path(FgapPlanRenderer.REGISTRY_PATH));
    }

    @Test
    void committedPlanIsByteEqualToAFreshRender() throws Exception {
        byte[] committed = Files.readAllBytes(TestRepo.path(FgapPlanRenderer.OUTPUT_PATH));
        String text = new String(committed, java.nio.charset.StandardCharsets.UTF_8);
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String rendered = FgapPlanRenderer.render(registry(), institutions(), eol);
        assertThat(rendered).isEqualTo(text);
        assertThat(rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(committed);
    }

    @Test
    void checkModeIgnoresLineEndings() throws Exception {
        String committed = Files.readString(TestRepo.path(FgapPlanRenderer.OUTPUT_PATH));
        assertThat(FgapPlanRenderer.isUpToDate(committed, registry(), institutions())).isTrue();
        assertThat(FgapPlanRenderer.isUpToDate(committed.replace("\r\n", "\n"), registry(), institutions())).isTrue();
        assertThat(FgapPlanRenderer.isUpToDate(committed + " ", registry(), institutions())).isFalse();
    }

    @Test
    void canonicalRenderUsesLfAndEndsWithNewline() {
        String rendered = FgapPlanRenderer.render(registry(), institutions());
        assertThat(rendered).doesNotContain("\r").endsWith("}\n").startsWith("{\n  \"_comment\": \"GENERATED FILE \\u2014");
    }

    @Test
    void staleWhenAnInstitutionIsAdded() throws Exception {
        String committed = Files.readString(TestRepo.path(FgapPlanRenderer.OUTPUT_PATH));
        List<PlanInstitution> more = new java.util.ArrayList<>(institutions());
        more.add(new PlanInstitution("zzz", List.of("app"), List.of()));
        assertThat(FgapPlanRenderer.isUpToDate(committed, registry(), more)).isFalse();
    }

    @Test
    void buildShapeAndSourcePaths() {
        Map<String, Object> plan = FgapPlanRenderer.build(registry(), List.of(
                new PlanInstitution("minisante", List.of("hmis"), List.of()),
                new PlanInstitution("minagri", List.of("kubaka-web"), List.of("planning"))));
        assertThat(plan.keySet()).containsExactly("_comment", "version", "source", "institutions", "policies",
                "permissions");
        assertThat(plan.get("institutions")).isEqualTo(List.of("minagri", "minisante"));
        assertThat(plan.get("source")).isEqualTo(Map.of("templates", "configs/delegated-permissions/templates.json",
                "registry", "configs/institutions.yaml"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) plan.get("permissions");
        assertThat(permissions.get(0).keySet()).containsExactly("institution", "name", "description",
                "resource_type", "resources", "scopes", "policies");
        assertThat(permissions).anySatisfy(p -> assertThat(p.get("resources")).isEqualTo(List.of("/minagri",
                "/minagri/planning")));
        assertThat(permissions.get(permissions.size() - 1).get("institution")).isEqualTo("_shared");
    }

    @Test
    void inputsSkipInactiveAndSeparateRealmEntries() {
        String yaml = """
                version: 1
                institutions:
                  - slug: a
                    apps: [{name: web}]
                    departments: [x, {name: y}]
                  - slug: b
                    status: suspended
                  - slug: c
                    separate_realm: true
                  - slug: d
                    status:
                  - display_name: no slug
                  - slug: e
                    status: active
                    separate_realm: false
                """;
        List<PlanInstitution> got = FgapPlanInputs.fromInstitutionsYaml(yaml);
        assertThat(got).extracting(PlanInstitution::slug).containsExactly("a", "e");
        assertThat(got.get(0).apps()).containsExactly("web");
        assertThat(got.get(0).departments()).containsExactly("x", "y");
        assertThat(FgapPlanInputs.fromInstitutionsYaml("version: 1\n")).isEmpty();
    }

    @Test
    void pyJsonMatchesPythonFormatting() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", List.of());
        m.put("b", Map.of());
        m.put("c", List.of(1, true, "x\"\\\n\té\u007f😀"));
        m.put("d", null);
        assertThat(PyJson.dumps(m, 2)).isEqualTo("""
                {
                  "a": [],
                  "b": {},
                  "c": [
                    1,
                    true,
                    "x\\"\\\\\\n\\t\\u00e9\\u007f\\ud83d\\ude00"
                  ],
                  "d": null
                }""");
    }

    @Test
    void sourceFilesAreFound() {
        assertThat(Path.of(TestRepo.path(FgapPlanRenderer.OUTPUT_PATH).toString())).exists();
    }
}
