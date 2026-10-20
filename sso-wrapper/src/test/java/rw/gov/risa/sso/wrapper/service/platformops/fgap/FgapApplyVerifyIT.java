package rw.gov.risa.sso.wrapper.service.platformops.fgap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.fgap.InstitutionPlan;
import rw.gov.risa.sso.wrapper.policy.fgap.SubgroupMode;
import rw.gov.risa.sso.wrapper.service.platformops.verify.KeycloakDirectVerifier;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * Definition of done (spec §5.3, §9): the FGAP v2 plan applied through the Admin API makes Keycloak itself
 * hold the institution boundary — the Keycloak-direct {@code verify} passes 9/9 against a Keycloak 26.2
 * Testcontainer, with no wrapper in the path.
 */
@Tag("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FgapApplyVerifyIT {

    static AdminRest master() {
        return new AdminRest(KeycloakIT.serverUrl(), KeycloakIT.REALM,
                () -> KeycloakIT.masterAdmin().tokenManager().getAccessTokenString());
    }

    static DelegatedPermissionRegistry registry() {
        return DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
    }

    static List<InstitutionPlan> plans(AdminRest kc) {
        return FgapApplier.livePlans(registry(), FgapApplier.inventory(kc), KeycloakIT.SLUGS, SubgroupMode.ENUMERATE);
    }

    @Test
    @Order(1)
    void applyCreatesThenConvergesIdempotently() {
        AdminRest kc = master();
        FgapApplier.Report first = new FgapApplier(kc, false).apply(plans(kc));
        first.lines().forEach(System.out::println);
        assertThat(first.problems()).isEmpty();
        // (another IT in this JVM may have applied first: assert the objects, not the creation count)
        String rs = kc.findClient(FgapApplier.ADMIN_PERMISSIONS_CLIENT).path("id").asText();
        assertThat(kc.requireGet("clients/" + rs + "/authz/resource-server/permission?name="
                + AdminRest.enc("inst:_shared:users:map-roles")).size()).isEqualTo(1);
        assertThat(kc.requireGet("clients/" + rs + "/authz/resource-server/policy?name="
                + AdminRest.enc("inst:minagri:institution-admin")).size()).isGreaterThanOrEqualTo(1);

        FgapApplier.Report again = new FgapApplier(kc, false).apply(plans(kc));
        assertThat(again.created()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(again.problems()).isEmpty();

        FgapApplier.Report dry = new FgapApplier(kc, true).apply(plans(kc));
        assertThat(dry.created() + dry.updated()).isZero();
    }

    @Test
    @Order(2)
    void verifyNineOfNine() {
        AdminRest kc = master();
        new FgapApplier(kc, false).apply(plans(kc));
        String token = AdminRest.passwordGrant(KeycloakIT.serverUrl(), KeycloakIT.REALM, KeycloakIT.TEST_CLIENT, null,
                "minagri.admin", KeycloakIT.PASSWORD).body().path("access_token").asText();
        KeycloakDirectVerifier.Report report = new KeycloakDirectVerifier(kc).verify(
                new KeycloakDirectVerifier.Options("minagri", "minisante", null, "minagri.admin", token));
        KeycloakDirectVerifier.render(report).forEach(System.out::println);
        assertThat(report.cases()).hasSize(9);
        assertThat(report.allPassed()).as(String.join("\n", KeycloakDirectVerifier.render(report))).isTrue();
    }
}
