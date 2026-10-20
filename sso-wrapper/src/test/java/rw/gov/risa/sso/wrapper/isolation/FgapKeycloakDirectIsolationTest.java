package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapPlanRenderer;
import rw.gov.risa.sso.wrapper.policy.fgap.InstitutionPlan;
import rw.gov.risa.sso.wrapper.policy.fgap.PlanInstitution;
import rw.gov.risa.sso.wrapper.policy.fgap.SubgroupMode;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionKeycloak;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionMembers;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionUsersService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminRest;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.FgapApplier;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * SPEC §5.3 / §7.1 — the Keycloak half of the boundary. The FGAP v2 plan is rendered from the repo's
 * {@code templates.json} ({@link FgapPlanRenderer}) and applied with the {@code fgap-apply} service
 * ({@link FgapApplier}) to the shared Keycloak 26.2 container. Then the REAL {@code minagri.admin} token
 * calls the Keycloak Admin API DIRECTLY — no wrapper in the path — and Keycloak itself must allow the own
 * institution and refuse the other one, for members, role mapping and clients. Finally the wrapper's
 * execute-as-caller path (which, without FGAP, Keycloak refuses) now succeeds for the own institution.
 */
@Tag("it")
class FgapKeycloakDirectIsolationTest {

    static AdminRest lookup;
    static AdminRest asMinagriAdmin;
    static String token;

    @BeforeAll
    static void applyThePlan() {
        KeycloakIT.container();
        lookup = new AdminRest(KeycloakIT.serverUrl(), KeycloakIT.REALM,
                () -> KeycloakIT.masterAdmin().tokenManager().getAccessTokenString());
        DelegatedPermissionRegistry registry = DelegatedPermissionsLoader.loadFrom(
                KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        String rendered = FgapPlanRenderer.render(registry, List.of(
                new PlanInstitution("minagri", List.of("kubaka-web"), List.of("planning")),
                new PlanInstitution("minisante", List.of("hmis"), List.of())));
        List<InstitutionPlan> plans = FgapApplier.fromPlanFile(rendered);
        assertThat(plans).extracting(InstitutionPlan::institution).contains("minagri", "minisante");
        FgapApplier.Report report = new FgapApplier(lookup, false).apply(plans);
        report.lines().forEach(System.out::println);
        assertThat(report.problems()).as("fgap-apply problems").isEmpty();

        token = AdminRest.passwordGrant(KeycloakIT.serverUrl(), KeycloakIT.REALM, KeycloakIT.TEST_CLIENT, null,
                "minagri.admin", KeycloakIT.PASSWORD).body().path("access_token").asText();
        asMinagriAdmin = lookup.as(() -> token);
    }

    @AfterEach
    void leaveRolesAsFound() {
        for (String user : List.of("minagri.staff", "minisante.staff")) {
            for (String role : List.of("inst:minagri:staff", "inst:minisante:staff", "inst:minagri:auditor")) {
                lookup.request("DELETE", "users/" + id(lookup.findUser(user)) + "/role-mappings/realm",
                        List.of(ref(role)));
            }
        }
    }

    private static String id(JsonNode n) {
        return n.path("id").asText();
    }

    private static Map<String, Object> ref(String role) {
        JsonNode r = lookup.findRealmRole(role);
        return Map.of("id", id(r), "name", r.path("name").asText());
    }

    private static int status(String method, String path, Object body) {
        return asMinagriAdmin.request(method, path, body).status();
    }

    // ---- members ---------------------------------------------------------------------------

    @Test
    void membersOfMyGroupAndDepartmentAreReadableTheOtherInstitutionsAreNot() {
        assertThat(status("GET", "groups/" + id(lookup.findGroup("/minagri")) + "/members", null)).isEqualTo(200);
        assertThat(status("GET", "groups/" + id(lookup.findGroup("/minagri/planning")) + "/members", null))
                .isEqualTo(200);
        assertThat(status("GET", "groups/" + id(lookup.findGroup("/minisante")) + "/members", null)).isEqualTo(403);
        assertThat(status("GET", "users", null)).isEqualTo(403);
    }

    @Test
    void myMemberIsReadableAnotherInstitutionsIsNot() {
        assertThat(status("GET", "users/" + id(lookup.findUser("minagri.staff")), null)).isEqualTo(200);
        assertThat(status("GET", "users/" + id(lookup.findUser("minisante.staff")), null)).isIn(403, 404);
    }

    // ---- role mapping ------------------------------------------------------------------------

    @Test
    void ownRoleToOwnMemberIsAllowedEverythingElseRefused() {
        String mine = "users/" + id(lookup.findUser("minagri.staff")) + "/role-mappings/realm";
        String theirs = "users/" + id(lookup.findUser("minisante.staff")) + "/role-mappings/realm";
        assertThat(status("POST", mine, List.of(ref("inst:minagri:staff")))).isEqualTo(204);
        assertThat(status("POST", mine, List.of(ref("inst:minisante:staff")))).isEqualTo(403);
        assertThat(status("POST", mine, List.of(ref("inst:minagri:admin")))).isEqualTo(403);
        assertThat(status("POST", mine, List.of(ref("super_admin")))).isEqualTo(403);
        assertThat(status("POST", theirs, List.of(ref("inst:minagri:staff")))).isEqualTo(403);
        assertThat(status("POST", theirs, List.of(ref("inst:minisante:staff")))).isEqualTo(403);
    }

    // ---- clients -----------------------------------------------------------------------------

    @Test
    void myClientIsReachableTheOtherInstitutionsIsNot() {
        String own = id(lookup.findClient("minagri-kubaka-web"));
        String other = id(lookup.findClient("minisante-hmis"));
        assertThat(status("GET", "clients/" + own, null)).isEqualTo(200);
        assertThat(status("GET", "clients/" + other, null)).isIn(403, 404);
        assertThat(status("GET", "clients/" + id(lookup.findClient("sso-client")), null)).isIn(403, 404);
    }

    // ---- the wrapper's execute-as-caller path, now that Keycloak holds the boundary too ------------

    private static InstitutionUsersService asCallerUsers() {
        DelegatedExecution delegated = new DelegatedExecution(KeycloakIT.executors(), new DelegationProperties(
                "configs/delegated-permissions/templates.json", "configs/institutions.yaml", true, true,
                "institution", true));
        InstitutionKeycloak kc = new InstitutionKeycloak(new KeycloakDirectory());
        return new InstitutionUsersService(kc, new InstitutionMembers(kc), delegated, mock(JoinKeyService.class),
                mock(AuditEmitter.class));
    }

    private static InstitutionContext minagriContext() {
        DelegatedPermissionRegistry registry = DelegatedPermissionsLoader.loadFrom(
                KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        return new InstitutionContext("minagri.admin", KeycloakIT.userId("minagri.admin"), "minagri",
                registry.templates().get("institution-admin").render("minagri"), List.of("minagri"), false,
                List.of("inst:minagri:admin"));
    }

    private static List<String> usernames(InstitutionUsersService users) {
        return users.listUsers(minagriContext(), KeycloakIT.caller("minagri.admin"), null).stream()
                .map(u -> String.valueOf(u.get("username"))).toList();
    }

    @Test
    void theWrappersAsCallerPathSucceedsForTheOwnInstitution() {
        InstitutionUsersService users = asCallerUsers();
        InstitutionContext ctx = minagriContext();
        CallerPrincipal caller = KeycloakIT.caller("minagri.admin");

        List<String> usernames = users.listUsers(ctx, caller, null).stream()
                .map(u -> String.valueOf(u.get("username"))).toList();
        assertThat(usernames).contains("minagri.staff", "minagri.planner").doesNotContain("minisante.staff");

        String staff = KeycloakIT.userId("minagri.staff");
        assertThat(users.getUser(ctx, caller, staff)).containsEntry("username", "minagri.staff");
        assertThat(users.assignRole(ctx, caller, staff, "inst:minagri:staff", null)).containsEntry("status", "assigned");
        assertThat(KeycloakIT.realm().users().get(staff).roles().realmLevel().listAll())
                .extracting(r -> r.getName()).contains("inst:minagri:staff");
    }

    /**
     * Regression: a department created AFTER the plan was applied (platform console, IPPIS) is not covered by
     * the enumerated FGAP v2 group permissions (26.2 does not propagate to children). Keycloak refuses the
     * delegated admin's token on it — the institution's listing must keep working without it (fail closed
     * for that department only), and re-applying the plan brings it in.
     */
    @Test
    void aDepartmentCreatedAfterTheApplyIsLeftOutUntilThePlanIsReappliedNeverBreakingTheListing() {
        String minagri = id(lookup.findGroup("/minagri"));
        String dept = KeycloakIT.unique("dept");
        String person = KeycloakIT.unique("deptuser");
        lookup.require("POST", "groups/" + minagri + "/children", AdminRest.map("name", dept));
        String deptId = id(lookup.findGroup("/minagri/" + dept));
        String userId = KeycloakIT.createUser(person, null);
        KeycloakIT.realm().users().get(userId).joinGroup(deptId);
        try {
            assertThat(asMinagriAdmin.request("GET", "groups/" + deptId + "/children", null).status()).isEqualTo(403);
            assertThat(usernames(asCallerUsers())).contains("minagri.staff", "minagri.planner").doesNotContain(person);

            // fgap-apply's default mode projects against the realm's inventory: the new department is enumerated
            FgapApplier.Report again = new FgapApplier(lookup, false).apply(FgapApplier.livePlans(
                    DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json")),
                    FgapApplier.inventory(lookup), KeycloakIT.SLUGS, SubgroupMode.ENUMERATE));
            assertThat(again.problems()).isEmpty();
            assertThat(usernames(asCallerUsers())).contains(person, "minagri.staff");
        } finally {
            lookup.request("DELETE", "users/" + userId, null);
            lookup.request("DELETE", "groups/" + deptId, null);
        }
    }
}
