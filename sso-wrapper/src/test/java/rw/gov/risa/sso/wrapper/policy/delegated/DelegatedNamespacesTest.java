package rw.gov.risa.sso.wrapper.policy.delegated;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DelegatedNamespacesTest {

    @ParameterizedTest
    @ValueSource(strings = {"minagri", "local-govt", "rra", "a1", "-"})
    void validSlugs(String slug) {
        assertThat(DelegatedNamespaces.isValidSlug(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MINAGRI", "min agri", "", "min/agri", "min:agri", "minagri\n", "min_agri"})
    void invalidSlugs(String slug) {
        assertThat(DelegatedNamespaces.isValidSlug(slug)).isFalse();
    }

    @Test
    void nullIsNotASlug() {
        assertThat(DelegatedNamespaces.isValidSlug(null)).isFalse();
    }

    @Test
    void prefixesAndRendering() {
        assertThat(DelegatedNamespaces.rolePrefix("minagri")).isEqualTo("inst:minagri:");
        assertThat(DelegatedNamespaces.clientPrefix("minagri")).isEqualTo("minagri-");
        assertThat(DelegatedNamespaces.groupPath("minagri")).isEqualTo("/minagri");
        assertThat(DelegatedNamespaces.render("inst:{slug}:{slug}", "x")).isEqualTo("inst:x:x");
    }

    @Test
    void parsesInstitutionRoles() {
        assertThat(DelegatedNamespaces.parseInstitutionRole("inst:minagri:staff"))
                .contains(new DelegatedNamespaces.InstitutionRole("minagri", "staff"));
        assertThat(DelegatedNamespaces.parseInstitutionRole("inst:minagri:staff").orElseThrow().roleName())
                .isEqualTo("inst:minagri:staff");
        assertThat(DelegatedNamespaces.parseInstitutionRole("inst:minagri:x:y")).isEmpty();
        assertThat(DelegatedNamespaces.parseInstitutionRole("inst:MINAGRI:staff")).isEmpty();
        assertThat(DelegatedNamespaces.parseInstitutionRole("inst:minagri:")).isEmpty();
        assertThat(DelegatedNamespaces.parseInstitutionRole("admin")).isEmpty();
        assertThat(DelegatedNamespaces.parseInstitutionRole(null)).isEmpty();
    }

    @Test
    void capabilityWireNames() {
        assertThat(Capability.fromWire("map-roles")).contains(Capability.MAP_ROLES);
        assertThat(Capability.fromWire("nope")).isEmpty();
        assertThat(Capability.MANAGE_CLIENTS.wire()).isEqualTo("manage-clients");
        assertThat(Capability.knownList()).isEqualTo(
                "view-members, manage-members, manage-membership, map-roles, view-clients, manage-clients");
    }
}
