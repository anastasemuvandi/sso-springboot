package rw.gov.risa.sso.wrapper.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedPermissionsHolder;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.platformops.delegated.DelegatedOpsService;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * The Keycloak-facing ops commands against the shared Keycloak 26.2 Testcontainer, run exactly as
 * {@code java -jar sso-wrapper.jar <command>} runs them (the wrapper's service account):
 * {@code delegated-apply} + {@code delegated-check} (clean, then a guardrail violation → non-zero), and
 * {@code fgap-apply} + {@code verify} (9/9, Keycloak-direct, no wrapper).
 */
@Tag("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformOpsCommandsIT {

    static final DelegationProperties PROPS = OfflineCommandsTest.PROPS;

    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    PrintStream out() {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    String printed() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static SsoProperties sso() {
        return KeycloakIT.ssoProperties();
    }

    static DelegatedOpsService service() {
        return new DelegatedOpsService(new DelegatedPermissionsHolder(PROPS), PROPS, KeycloakIT.executors(), sso());
    }

    @Test
    @Order(1)
    void delegatedApplyCreatesAnInstitutionsRolesIdempotently() {
        String slug = KeycloakIT.unique("dlg");
        DelegatedApplyCommand apply = new DelegatedApplyCommand(service(), PROPS, sso());
        int code = apply.printTo(out()).run(List.of("--institution", slug));
        assertThat(code).as(printed()).isZero();
        RealmResource realm = KeycloakIT.realm();
        for (String role : List.of("admin", "hr", "staff", "auditor")) {
            assertThat(realm.roles().get("inst:" + slug + ":" + role).toRepresentation().getDescription())
                    .contains("Delegated permissions");
        }
        buffer.reset();
        assertThat(new DelegatedApplyCommand(service(), PROPS, sso()).printTo(out())
                .run(List.of("--institution", slug))).isZero();
        assertThat(printed()).contains("0 role(s) created");

        // dry run on a fresh slug writes nothing
        String dry = KeycloakIT.unique("dry");
        buffer.reset();
        assertThat(new DelegatedApplyCommand(service(), PROPS, sso()).printTo(out())
                .run(List.of("--institution", dry, "--dry-run"))).isZero();
        assertThat(printed()).contains("would create");
        assertThat(realm.roles().list().stream().map(RoleRepresentation::getName)).noneMatch(n -> n.contains(dry));
    }

    @Test
    @Order(2)
    void delegatedCheckIsCleanThenFailsOnAGuardrailViolation() {
        String slug = KeycloakIT.unique("chk");
        assertThat(new DelegatedApplyCommand(service(), PROPS, sso()).printTo(out())
                .run(List.of("--institution", slug))).isZero();
        String holder = KeycloakIT.createUser(KeycloakIT.unique("holder"), null, "inst:" + slug + ":admin");
        buffer.reset();
        int clean = new DelegatedCheckCommand(service(), PROPS).printTo(out()).run(List.of("--institution", slug));
        assertThat(clean).as(printed()).isZero();
        assertThat(printed()).contains("Result: CLEAN").contains("OK ");

        // the local admin acquires a realm-management role: Keycloak-side privilege escalation
        RealmResource realm = KeycloakIT.realm();
        ClientRepresentation rm = realm.clients().findByClientId("realm-management").get(0);
        RoleRepresentation viewUsers = realm.clients().get(rm.getId()).roles().get("view-users").toRepresentation();
        realm.users().get(holder).roles().clientLevel(rm.getId()).add(List.of(viewUsers));
        buffer.reset();
        int found = new DelegatedCheckCommand(service(), PROPS).printTo(out()).run(List.of("--institution", slug));
        assertThat(found).isEqualTo(1);
        assertThat(printed()).contains("GUARDRAIL VIOLATIONS").contains("realm-management:view-users")
                .contains("FINDINGS PRESENT");
        realm.users().get(holder).roles().clientLevel(rm.getId()).remove(List.of(viewUsers));
    }

    @Test
    @Order(3)
    void fgapApplyThenVerifyNineOfNine() {
        InstitutionRegistryHolder registry = new InstitutionRegistryHolder(PROPS);
        registry.reload();
        FgapApplyCommand apply = new FgapApplyCommand(PROPS, registry, sso(), KeycloakIT.adminFactory());
        int applied = apply.printTo(out()).run(List.of("--institution", "minagri", "--institution", "minisante"));
        assertThat(applied).as(printed()).isZero();

        buffer.reset();
        VerifyCommand verify = new VerifyCommand(sso(), KeycloakIT.adminFactory());
        int code = verify.printTo(out()).run(List.of("--institution", "minagri", "--institution", "minisante",
                "--password", KeycloakIT.PASSWORD, "--client-id", KeycloakIT.TEST_CLIENT));
        System.out.println(printed());
        assertThat(code).as(printed()).isZero();
        assertThat(printed()).contains("9/9 passed").contains("no wrapper in the path");
    }

    @Test
    @Order(4)
    void verifyFailsClosedWhenTheAdminCannotLogIn() {
        int code = new VerifyCommand(sso(), KeycloakIT.adminFactory()).printTo(out()).run(List.of(
                "--institution", "minagri", "--institution", "minisante", "--password", "wrong-password",
                "--client-id", KeycloakIT.TEST_CLIENT));
        assertThat(code).isEqualTo(2);
        assertThat(printed()).contains("cannot log in");
    }

    @Test
    @Order(5)
    void verifyReportsABrokenBoundary() {
        // A super_admin is NOT bounded by FGAP: the isolation cases must FAIL (non-zero), proving the
        // command really reads Keycloak's answers rather than printing a fixed report.
        String user = KeycloakIT.unique("boundless");
        KeycloakIT.createUser(user, "minagri", "inst:minagri:admin");
        RealmResource realm = KeycloakIT.realm();
        ClientRepresentation rm = realm.clients().findByClientId("realm-management").get(0);
        RoleRepresentation realmAdmin = realm.clients().get(rm.getId()).roles().get("realm-admin").toRepresentation();
        realm.users().get(KeycloakIT.userId(user)).roles().clientLevel(rm.getId()).add(List.of(realmAdmin));
        int code = new VerifyCommand(sso(), KeycloakIT.adminFactory()).printTo(out()).run(List.of(
                "--institution", "minagri", "--institution", "minisante", "--username", user,
                "--password", KeycloakIT.PASSWORD, "--client-id", KeycloakIT.TEST_CLIENT));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("[FAIL] list members of OTHER institution").contains("FAILURES ABOVE");
    }

    @Test
    @Order(6)
    void fgapApplyOfTheCommittedPlanReportsWhatTheRealmLacks() {
        // The committed plan names nine institutions; this realm onboarded two. Applying it must not guess:
        // the absent objects are reported (exit 1) and the two present institutions still apply.
        int code = new FgapApplyCommand(PROPS, new InstitutionRegistryHolder(PROPS), sso(), KeycloakIT.adminFactory())
                .printTo(out()).run(List.of("--plan", OfflineCommandsTest.repo("configs/generated/fgap-v2-plan.json")
                        .toString(), "--institution", "minagri", "--institution", "minisante", "--dry-run"));
        assertThat(code).as(printed()).isEqualTo(1);
        assertThat(printed()).contains("/minagri/extension");
    }
}
