package rw.gov.risa.sso.wrapper.policy.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryValidatorTest {

    private static final Map<String, Object> TEMPLATES = Map.of(
            "templates", Map.of("standard", Map.of(), "extended", Map.of()),
            "assignments", Map.of("default", List.of("standard"),
                    "by_institution", Map.of("minagri", List.of("standard", "extended"))));

    private static Map<String, Object> inst(String slug) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("display_name", "Name of " + slug);
        m.put("ippis_codes", new ArrayList<>(List.of(slug.toUpperCase())));
        m.put("status", "active");
        return m;
    }

    private static Map<String, Object> app(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    private static RegistryFindings validate(Object... entries) {
        return RegistryValidator.validate(Map.of("institutions", List.of(entries)), TEMPLATES);
    }

    @Test
    void aCleanRegistryHasNoFindings() {
        Map<String, Object> a = inst("minagri");
        a.put("apps", List.of(app("kubaka-web")));
        a.put("departments", List.of("planning"));
        RegistryFindings f = validate(a, inst("minisante"));
        assertThat(f.errors()).isEmpty();
        assertThat(f.warnings()).isEmpty();
        assertThat(f.isClean()).isTrue();
        assertThat(f.exitCode(true)).isZero();
    }

    @Test
    void documentShape() {
        assertThat(RegistryValidator.validate(Map.of("x", 1), TEMPLATES).errors())
                .singleElement().asString().contains("no top-level `institutions:` key");
        assertThat(RegistryValidator.validate(Map.of("institutions", List.of()), TEMPLATES).errors())
                .singleElement().asString().contains("lists no institutions");
        assertThat(RegistryValidator.validate(null, TEMPLATES).isClean()).isFalse();
    }

    @Test
    void entryShapeErrors() {
        Map<String, Object> noSlug = new LinkedHashMap<>();
        noSlug.put("display_name", "x");
        RegistryFindings f = validate("not a map", noSlug);
        assertThat(f.errors()).contains("institutions[0] is not a mapping.", "institutions[1] has no `slug`.");
    }

    @Test
    void invalidSlugsAreErrors() {
        for (String bad : List.of("MINAGRI", "min_agri", "min.agri", "-x", "min agri")) {
            assertThat(validate(inst(bad)).errors()).as(bad).anyMatch(e -> e.startsWith(bad + ": invalid slug. ADR-0001 §5.1"));
        }
    }

    @Test
    void displayNameRequiredAndStatusVocabulary() {
        Map<String, Object> e = inst("minagri");
        e.remove("display_name");
        e.put("status", "retired");
        RegistryFindings f = validate(e);
        assertThat(f.errors()).contains("minagri: `display_name` is required.",
                "minagri: status 'retired' is not one of ['active', 'decommissioned', 'suspended'].");
    }

    @Test
    void codesMustBeAListAndActiveWithoutCodesWarns() {
        Map<String, Object> e = inst("minagri");
        e.put("ippis_codes", "MINAGRI");
        assertThat(validate(e).errors()).contains("minagri: `ippis_codes` must be a list.");
        Map<String, Object> none = inst("minisante");
        none.remove("ippis_codes");
        assertThat(validate(none).warnings())
                .contains("minisante: no `ippis_codes`; IPPIS inbound sync cannot map anyone here.");
        Map<String, Object> suspended = inst("rdb");
        suspended.put("ippis_codes", List.of());
        suspended.put("status", "suspended");
        assertThat(validate(suspended).warnings()).noneMatch(w -> w.contains("ippis_codes"));
    }

    @Test
    void separateRealmNeedsJustification() {
        Map<String, Object> e = inst("minagri");
        e.put("separate_realm", true);
        assertThat(validate(e).errors()).anyMatch(m -> m.startsWith("minagri: separate_realm requires a `justification`"));
        e.put("justification", "Law 123");
        assertThat(validate(e).errors()).isEmpty();
    }

    @Test
    void departmentsMustBeSlugs() {
        Map<String, Object> e = inst("minagri");
        e.put("departments", List.of("ok", "Bad Dept", 7));
        assertThat(validate(e).errors()).containsExactly(
                "minagri: department 'Bad Dept' must be lowercase [a-z0-9-].",
                "minagri: department 7 must be lowercase [a-z0-9-].");
    }

    @Test
    void appRules() {
        Map<String, Object> e = inst("minagri");
        Map<String, Object> pub = app("portal");
        pub.put("public", true);
        pub.put("service_accounts", true);
        Map<String, Object> local = app("web");
        local.put("redirect_uris", List.of("http://localhost:3000/*", "*", "https://ok.gov.rw/*"));
        Map<String, Object> dag = app("legacy");
        dag.put("direct_access_grants", true);
        e.put("apps", List.of(app("Bad"), app("minagri-dup"), app("x"), app("x"), Map.of(), pub, local, dag));
        RegistryFindings f = validate(e);
        assertThat(f.errors()).contains(
                "minagri: app name 'Bad' must be lowercase [a-z0-9-].",
                "minagri: duplicate app 'x'.",
                "minagri: every app needs a `name`.",
                "minagri/portal: a public client cannot have service accounts.",
                "minagri/web: redirect URI '*' is an open redirect.");
        assertThat(f.errors()).anyMatch(m -> m.startsWith("minagri/web: redirect URI 'http://localhost:3000/*' is environment-specific."));
        assertThat(f.warnings()).anyMatch(m -> m.startsWith("minagri: app 'minagri-dup' already starts with the slug; the client-id becomes 'minagri-minagri-dup'."));
        assertThat(f.warnings()).anyMatch(m -> m.startsWith("minagri/portal: public clients carry no access policy"));
        assertThat(f.warnings()).anyMatch(m -> m.startsWith("minagri/legacy: direct access grants (password grant) enabled"));
    }

    @Test
    void publicClientWithEnforceAccessFalseDoesNotWarn() {
        Map<String, Object> e = inst("minagri");
        Map<String, Object> pub = app("spa");
        pub.put("public", true);
        pub.put("enforce_access", false);
        e.put("apps", List.of(pub));
        assertThat(validate(e).warnings()).isEmpty();
    }

    @Test
    void appsMustBeAList() {
        Map<String, Object> e = inst("minagri");
        e.put("apps", "kubaka");
        assertThat(validate(e).errors()).contains("minagri: `apps` must be a list.");
    }

    @Test
    void duplicateSlugs() {
        assertThat(validate(inst("minagri"), inst("minagri")).errors())
                .contains("slug 'minagri' appears 2 times; it identifies one institution.");
    }

    @Test
    void ippisCodeUniqueness() {
        Map<String, Object> a = inst("minagri");
        a.put("ippis_codes", List.of("shared"));
        Map<String, Object> b = inst("minisante");
        b.put("ippis_codes", List.of("SHARED"));
        assertThat(validate(a, b).errors()).contains(
                "IPPIS code 'SHARED' maps to ['minagri', 'minisante']. Many codes may point at one slug, never the reverse (ADR-0001 §5.2).");
        Map<String, Object> c = inst("rdb");
        c.put("ippis_codes", List.of("RDB", "RDB-HQ"));
        assertThat(validate(c).errors()).isEmpty();
    }

    @Test
    void clientIdCollisionsAcrossActiveInstitutionsOnly() {
        Map<String, Object> a = inst("ab");
        a.put("apps", List.of(app("c-d")));
        Map<String, Object> b = inst("ab-c");
        b.put("apps", List.of(app("d")));
        RegistryFindings f = validate(a, b);
        assertThat(f.errors()).contains("client-id 'ab-c-d' claimed by ['ab', 'ab-c']; client-ids are realm-global.");
        b.put("status", "decommissioned");
        assertThat(validate(a, b).errors()).noneMatch(e -> e.startsWith("client-id"));
    }

    @Test
    void hyphenPrefixCollisionIsAnIsolationError() {
        RegistryFindings f = validate(inst("local"), inst("local-govt"), inst("localgov"));
        assertThat(f.errors()).containsExactly(
                "slug 'local-govt' starts with 'local' + '-'. Client scoping is a glob ('local-*'), so 'local''s admins "
                        + "would match 'local-govt''s clients. Choose a slug that is not an extension of an existing one.");
    }

    @Test
    void tiers() {
        RegistryFindings f = RegistryValidator.validate(Map.of("institutions", List.of(inst("minagri"))),
                Map.of("templates", Map.of("standard", Map.of()),
                        "assignments", Map.of("by_institution", Map.of("minagri", List.of("ghost")))));
        assertThat(f.errors()).contains("minagri: delegation tier 'ghost' is not defined in templates.json.");

        RegistryFindings none = RegistryValidator.validate(Map.of("institutions", List.of(inst("rdb"))),
                Map.of("templates", Map.of("standard", Map.of()), "assignments", Map.of()));
        assertThat(none.warnings()).contains(
                "rdb: no delegation tiers assigned, so it gets no inst:rdb:* roles and no local admin can ever be appointed.");
        assertThat(none.isClean()).isTrue();
        assertThat(none.isClean(true)).isFalse();
        assertThat(none.exitCode(true)).isEqualTo(1);

        assertThat(RegistryValidator.validate(Map.of("institutions", List.of(inst("rdb"))), Map.of()).errors())
                .containsExactly("templates.json defines no templates.");
    }

    @Test
    void tiersForPrefersByInstitution() {
        assertThat(RegistryValidator.tiersFor("minagri", TEMPLATES)).containsExactly("standard", "extended");
        assertThat(RegistryValidator.tiersFor("rdb", TEMPLATES)).containsExactly("standard");
        assertThat(RegistryValidator.tiersFor("rdb", null)).isEmpty();
    }
}
