package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.PermissionTemplate;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistryLoader;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * SPEC §7.1 — structural assertions on the REAL templates for concrete institution pairs: a config that
 * granted A a pattern matching B would make every grant-based test pass while the system leaked.
 */
class ShippedConfigCannotReachAnotherInstitutionIsolationTest {

    static final String A = "minagri";
    static final String B = "minisante";
    static DelegatedPermissionRegistry shipped;

    @BeforeAll
    static void load() {
        shipped = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
    }

    private static List<ResolvedGrant> tiersOf(String slug) {
        List<ResolvedGrant> out = new ArrayList<>();
        for (PermissionTemplate t : shipped.templates().values()) {
            out.add(t.render(slug));
        }
        return out;
    }

    @Test
    void noTierOfACanMapARoleOfB() {
        for (ResolvedGrant g : tiersOf(A)) {
            for (String role : List.of("inst:minisante:admin", "inst:minisante:hr", "inst:minisante:staff")) {
                assertThat(g.mayMapRole(role)).as(g.templates() + " may map " + role).isFalse();
            }
        }
    }

    @Test
    void noTierOfACanTouchAClientOfB() {
        for (ResolvedGrant g : tiersOf(A)) {
            for (String client : List.of(B + "-hmis", B + "-lab")) {
                assertThat(g.mayViewClient(client)).isFalse();
                assertThat(g.mayManageClient(client)).isFalse();
            }
        }
    }

    @Test
    void noTierOfACoversBsGroup() {
        for (ResolvedGrant g : tiersOf(A)) {
            assertThat(g.coversGroupPath("/" + B)).isFalse();
            assertThat(g.coversGroupPath("/" + B + "/clinical")).isFalse();
            assertThat(g.coversGroupPath("/" + A + "-legacy")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"super_admin", "admin", "system_admin", "realm-admin", "manage-users", "mfa-exempt"})
    void noTierCanMapABroadRole(String role) {
        for (ResolvedGrant g : tiersOf(A)) {
            assertThat(g.mayMapRole(role)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sso-admin-ui", "sso-client", "realm-management"})
    void noTierCanManageAControlPlaneClient(String clientId) {
        for (ResolvedGrant g : tiersOf(A)) {
            assertThat(g.mayManageClient(clientId)).isFalse();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyInstitutionInTheRegistryIsIsolatedFromEveryOther() throws Exception {
        Path file = KeycloakIT.locate("configs/institutions.yaml");
        Map<String, Object> doc = (Map<String, Object>) InstitutionRegistryLoader.readRaw(file);
        List<String> slugs = new ArrayList<>();
        for (Object e : (List<Object>) doc.get("institutions")) {
            Map<String, Object> entry = (Map<String, Object>) e;
            if ("active".equals(String.valueOf(entry.getOrDefault("status", "active")))) {
                slugs.add(String.valueOf(entry.get("slug")));
            }
        }
        assertThat(slugs).contains(A, B);
        for (String mine : slugs) {
            for (String theirs : slugs) {
                if (mine.equals(theirs)) {
                    continue;
                }
                for (ResolvedGrant g : tiersOf(mine)) {
                    assertThat(g.coversGroupPath("/" + theirs)).as(mine + " covers /" + theirs).isFalse();
                    assertThat(g.mayMapRole("inst:" + theirs + ":staff")).isFalse();
                    assertThat(g.mayManageClient(theirs + "-portal")).isFalse();
                }
            }
        }
    }
}
