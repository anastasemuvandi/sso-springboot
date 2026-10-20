package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistryLoader;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryFindings;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryValidator;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * SPEC §7.1 — role scoping ({@code inst:<slug>:}) and member scoping ({@code /<slug>/}) bind to exactly
 * one institution, but client scoping ({@code <slug>-*}) does not: {@code -} can occur inside a slug.
 * That is compensated one level up — a slug that extends another is rejected at onboarding. These tests
 * hold the compensating control in place.
 */
class ClientScopeIsPrefixBasedAndTheRegistryCompensatesIsolationTest {

    static DelegatedPermissionRegistry shipped;
    static ResolvedGrant adminOfA;

    @BeforeAll
    static void load() {
        shipped = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        adminOfA = shipped.templates().get("institution-admin").render("minagri");
    }

    @Test
    void theGlobDoesMatchADeeperHyphen() {
        assertThat(adminOfA.mayManageClient("minagri-legacy-portal")).isTrue();
    }

    @Test
    void roleScopingHasNoSuchAmbiguity() {
        assertThat(adminOfA.mayMapRole("inst:minagri-legacy:staff")).isFalse();
    }

    @Test
    void memberScopingHasNoSuchAmbiguity() {
        assertThat(adminOfA.coversGroupPath("/minagri-legacy")).isFalse();
        assertThat(adminOfA.coversGroupPath("/minagri-legacy/planning")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRegistryValidatorRejectsAnExtendingSlug() throws Exception {
        Object registry = InstitutionRegistryLoader.parseYaml("version: 1\ninstitutions:\n"
                + "  - slug: minagri\n    display_name: A\n    ippis_codes: [MINAGRI]\n    status: active\n"
                + "  - slug: minagri-legacy\n    display_name: A Legacy\n    ippis_codes: [ALEGACY]\n    status: active\n");
        Map<String, ?> templates = new ObjectMapper().readValue(
                Files.readString(KeycloakIT.locate("configs/delegated-permissions/templates.json")), Map.class);
        RegistryFindings f = RegistryValidator.validate(registry, templates);
        assertThat(f.errors()).anyMatch(e -> e.contains("starts with") && e.contains("minagri-legacy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theShippedRegistryHasNoExtendingSlug() throws Exception {
        Path file = KeycloakIT.locate("configs/institutions.yaml");
        Map<String, Object> doc = (Map<String, Object>) InstitutionRegistryLoader.readRaw(file);
        List<String> slugs = new ArrayList<>();
        for (Object e : (List<Object>) doc.get("institutions")) {
            slugs.add(String.valueOf(((Map<String, Object>) e).get("slug")));
        }
        List<String> collisions = new ArrayList<>();
        for (String outer : slugs) {
            for (String inner : slugs) {
                if (!inner.equals(outer) && inner.startsWith(outer + "-")) {
                    collisions.add(outer + " / " + inner);
                }
            }
        }
        assertThat(collisions).as("the shorter slug's admins can manage the longer slug's clients").isEmpty();
    }
}
