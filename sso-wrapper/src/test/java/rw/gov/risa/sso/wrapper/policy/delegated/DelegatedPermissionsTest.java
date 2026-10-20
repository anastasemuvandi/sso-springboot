package rw.gov.risa.sso.wrapper.policy.delegated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of tests/e2e/test_delegated_permissions.py (pure parts) plus extra edge cases. */
class DelegatedPermissionsTest {

    private final DelegatedPermissionRegistry registry = Fixtures.registry();

    private ResolvedGrant render(String template, String slug) {
        return registry.templates().get(template).render(slug);
    }

    @Nested
    class TemplateRendering {
        @Test
        void slugPlaceholderIsSubstitutedEverywhere() {
            ResolvedGrant g = render("local-admin", "minagri");
            assertThat(g.institution()).isEqualTo("minagri");
            assertThat(g.groupPath()).isEqualTo("/minagri");
            assertThat(g.roleAllow()).containsExactly("inst:minagri:*");
            assertThat(g.roleDeny()).containsExactly("inst:minagri:admin");
            assertThat(g.clientAllow()).containsExactly("minagri-*");
            assertThat(g.clientDeny()).containsExactly("minagri-internal");
            assertThat(g.templates()).containsExactly("local-admin");
        }

        @Test
        void sameTemplateRendersIndependentlyPerInstitution() {
            ResolvedGrant minagri = render("local-admin", "minagri");
            ResolvedGrant minisante = render("local-admin", "minisante");
            assertThat(minagri.mayMapRole("inst:minagri:staff")).isTrue();
            assertThat(minagri.mayMapRole("inst:minisante:staff")).isFalse();
            assertThat(minisante.mayMapRole("inst:minisante:staff")).isTrue();
            assertThat(minisante.mayMapRole("inst:minagri:staff")).isFalse();
        }

        @Test
        void grantRoleIdentifiesTheInstitution() {
            PermissionTemplate t = registry.templates().get("local-admin");
            assertThat(t.slugForRole("inst:minagri:admin")).contains("minagri");
            assertThat(t.grantRoleFor("minagri")).isEqualTo("inst:minagri:admin");
            assertThat(t.slugForRole("inst:minagri:hr")).isEmpty();
            assertThat(t.slugForRole("admin")).isEmpty();
            assertThat(t.slugForRole("inst:minagri:x:admin")).isEmpty();
            assertThat(t.slugForRole("inst:MINAGRI:admin")).isEmpty();
            assertThat(t.slugForRole("inst::admin")).isEmpty();
            assertThat(t.slugForRole("inst:minagri:admin\n")).isEmpty();
            assertThat(t.slugForRole(null)).isEmpty();
            assertThat(t.slugForRole("inst:local-govt:admin")).contains("local-govt");
        }

        @Test
        void regexMetacharactersInTheGrantRoleAreLiteral() {
            PermissionTemplate t = new PermissionTemplate("x", "x", "", "inst:{slug}:a.b+", java.util.Set.of(),
                    "/{slug}", true, List.of(), List.of(), List.of(), List.of(), List.of());
            assertThat(t.slugForRole("inst:s:a.b+")).contains("s");
            assertThat(t.slugForRole("inst:s:aXbb")).isEmpty();
        }

        @Test
        void rolesToProvisionListsGrantRoleFirstAndDedupes() {
            List<String> roles = registry.templates().get("local-admin").rolesToProvision("minagri");
            assertThat(roles).containsExactly("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff");
            assertThat(registry.templates().get("local-hr").rolesToProvision("x"))
                    .containsExactly("inst:x:hr", "inst:x:staff");
        }
    }

    @Nested
    class MemberScoping {
        @Test
        void institutionGroupIsInScope() {
            assertThat(render("local-admin", "minagri").coversGroupPath("/minagri")).isTrue();
        }

        @Test
        void otherInstitutionsGroupIsNot() {
            ResolvedGrant g = render("local-admin", "minagri");
            assertThat(g.coversGroupPath("/minisante")).isFalse();
            assertThat(g.coversGroupPath("/")).isFalse();
            assertThat(g.coversGroupPath(null)).isFalse();
            assertThat(g.coversGroupPath("")).isFalse();
        }

        @Test
        void aPrefixCollisionIsNotASubgroup() {
            assertThat(render("local-admin", "minagri").coversGroupPath("/minagri-old")).isFalse();
        }

        @Test
        void subgroupsFollowTheTemplateFlag() {
            assertThat(render("local-admin", "minagri").coversGroupPath("/minagri/finance")).isTrue();
            assertThat(render("local-admin", "minagri").coversGroupPath("/minagri/finance/x")).isTrue();
            assertThat(render("local-hr", "minagri").coversGroupPath("/minagri/finance")).isFalse();
            assertThat(render("local-hr", "minagri").coversGroupPath("/minagri")).isTrue();
        }

        @Test
        void viewMembersGatesReading() {
            ResolvedGrant auditor = render("local-auditor", "minagri");
            assertThat(auditor.has(Capability.VIEW_MEMBERS)).isTrue();
            assertThat(auditor.has(Capability.MANAGE_MEMBERS)).isFalse();
            assertThat(auditor.has(Capability.MANAGE_MEMBERSHIP)).isFalse();
        }
    }

    @Nested
    class MapRolesScoping {
        @ParameterizedTest
        @ValueSource(strings = {"admin", "super_admin", "system_admin", "manage-users", "realm-admin",
                "offline_access", "inst:minisante:staff", "inst:minagri", "instminagristaff"})
        void aLocalAdminCannotAssignAnythingOutsideItsNamespace(String role) {
            assertThat(render("local-admin", "minagri").mayMapRole(role)).isFalse();
        }

        @Test
        void itCanAssignItsOwnRoles() {
            ResolvedGrant g = render("local-admin", "minagri");
            assertThat(g.mayMapRole("inst:minagri:staff")).isTrue();
            assertThat(g.mayMapRole("inst:minagri:hr")).isTrue();
        }

        @Test
        void denyBeatsAllow() {
            assertThat(render("local-admin", "minagri").mayMapRole("inst:minagri:admin")).isFalse();
        }

        @Test
        void aNarrowerTierAssignsOnlyWhatItLists() {
            ResolvedGrant hr = render("local-hr", "minagri");
            assertThat(hr.mayMapRole("inst:minagri:staff")).isTrue();
            assertThat(hr.mayMapRole("inst:minagri:hr")).isFalse();
            assertThat(hr.mayMapRole("inst:minagri:admin")).isFalse();
        }

        @Test
        void withoutTheCapabilityNothingIsAssignable() {
            ResolvedGrant auditor = render("local-auditor", "minagri");
            assertThat(auditor.has(Capability.MAP_ROLES)).isFalse();
            assertThat(auditor.mayMapRole("inst:minagri:staff")).isFalse();
        }
    }

    @Nested
    class ClientScoping {
        @Test
        void ownClientsAreManageable() {
            ResolvedGrant g = render("local-admin", "minagri");
            assertThat(g.mayViewClient("minagri-kubaka-web")).isTrue();
            assertThat(g.mayManageClient("minagri-kubaka-web")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"minisante-hmis", "sso-admin-ui", "sso-client", "security-admin-console", "minagri"})
        void otherClientsAreRefused(String clientId) {
            ResolvedGrant g = render("local-admin", "minagri");
            assertThat(g.mayViewClient(clientId)).isFalse();
            assertThat(g.mayManageClient(clientId)).isFalse();
        }

        @Test
        void denyExcludesAClientInsideTheNamespace() {
            assertThat(render("local-admin", "minagri").mayManageClient("minagri-internal")).isFalse();
            assertThat(render("local-admin", "minagri").mayViewClient("minagri-internal")).isFalse();
        }

        @Test
        void readOnlyTierSeesButCannotManage() {
            ResolvedGrant auditor = render("local-auditor", "minagri");
            assertThat(auditor.mayViewClient("minagri-kubaka-web")).isTrue();
            assertThat(auditor.mayManageClient("minagri-kubaka-web")).isFalse();
        }

        @Test
        void aTierWithNoClientScopeSeesNothing() {
            ResolvedGrant hr = render("local-hr", "minagri");
            assertThat(hr.mayViewClient("minagri-kubaka-web")).isFalse();
            assertThat(hr.mayManageClient("minagri-kubaka-web")).isFalse();
        }

        @Test
        void sharedSubstitutesForTheAllowPatternOnly() {
            ResolvedGrant admin = render("local-admin", "minagri");
            assertThat(admin.mayManageClient("national-payroll")).isFalse();
            assertThat(admin.mayManageClient("national-payroll", true)).isTrue();
            assertThat(admin.mayViewClient("national-payroll", true)).isTrue();
            // denies still win however the client came into reach
            assertThat(admin.mayManageClient("minagri-internal", true)).isFalse();
            // the capability is still required
            assertThat(render("local-auditor", "minagri").mayManageClient("national-payroll", true)).isFalse();
            assertThat(render("local-hr", "minagri").mayViewClient("national-payroll", true)).isFalse();
        }
    }

    @Nested
    class MergeGrants {
        @Test
        void capabilitiesUnion() {
            ResolvedGrant merged = ResolvedGrant.merge(List.of(render("local-hr", "minagri"),
                    render("local-auditor", "minagri")));
            assertThat(merged.has(Capability.MANAGE_MEMBERS)).isTrue();
            assertThat(merged.has(Capability.VIEW_CLIENTS)).isTrue();
            assertThat(merged.has(Capability.MANAGE_CLIENTS)).isFalse();
            assertThat(merged.templates()).containsExactly("local-hr", "local-auditor");
            // include_subgroups is "any"
            assertThat(merged.includeSubgroups()).isTrue();
        }

        @Test
        void aDenyInAnyTierStillWins() {
            ResolvedGrant merged = ResolvedGrant.merge(List.of(render("local-admin", "minagri"),
                    render("local-hr", "minagri")));
            assertThat(merged.mayMapRole("inst:minagri:staff")).isTrue();
            assertThat(merged.mayMapRole("inst:minagri:admin")).isFalse();
            assertThat(merged.roleAllow()).containsExactly("inst:minagri:*", "inst:minagri:staff");
        }

        @Test
        void refusesToMergeAcrossInstitutions() {
            assertThatThrownBy(() -> ResolvedGrant.merge(List.of(render("local-admin", "minagri"),
                    render("local-admin", "minisante")))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesAnEmptyMergeAndReturnsASingleGrantAsIs() {
            assertThatThrownBy(() -> ResolvedGrant.merge(List.of())).isInstanceOf(IllegalArgumentException.class);
            ResolvedGrant one = render("local-admin", "minagri");
            assertThat(ResolvedGrant.merge(List.of(one))).isSameAs(one);
        }

        @Test
        void mergingTheSameTemplateTwiceDedupes() {
            ResolvedGrant merged = ResolvedGrant.merge(List.of(render("local-admin", "minagri"),
                    render("local-admin", "minagri")));
            assertThat(merged.templates()).containsExactly("local-admin");
            assertThat(merged.roleAllow()).containsExactly("inst:minagri:*");
        }
    }

    @Nested
    class ResolveCaller {
        @Test
        void grantRolesMapToInstitutions() {
            Map<String, ResolvedGrant> grants = registry.resolveCaller(List.of("inst:minagri:admin", "employee"));
            assertThat(grants).containsOnlyKeys("minagri");
            assertThat(grants.get("minagri").has(Capability.MANAGE_CLIENTS)).isTrue();
        }

        @Test
        void severalInstitutionsResolveSeparately() {
            Map<String, ResolvedGrant> grants = registry.resolveCaller(List.of("inst:minagri:admin",
                    "inst:minisante:hr"));
            assertThat(grants.keySet()).containsExactly("minagri", "minisante");
            assertThat(grants.get("minisante").has(Capability.MANAGE_MEMBERS)).isTrue();
            assertThat(grants.get("minisante").has(Capability.MANAGE_CLIENTS)).isFalse();
        }

        @Test
        void noGrantRoleMeansNoInstitution() {
            assertThat(registry.resolveCaller(List.of("admin", "super_admin"))).isEmpty();
            assertThat(registry.resolveCaller(null)).isEmpty();
        }

        @Test
        void aWithheldTierIsIgnoredEvenIfTheRoleIsHeld() {
            assertThat(registry.resolveCaller(List.of("inst:mineduc:admin"))).isEmpty();
            Map<String, ResolvedGrant> grants = registry.resolveCaller(List.of("inst:mineduc:admin",
                    "inst:mineduc:hr"));
            assertThat(grants).containsOnlyKeys("mineduc");
            assertThat(grants.get("mineduc").templates()).containsExactly("local-hr");
            assertThat(grants.get("mineduc").has(Capability.MANAGE_CLIENTS)).isFalse();
        }

        @Test
        void twoTiersInOneInstitutionMerge() {
            ResolvedGrant g = registry.resolveCaller(List.of("inst:minagri:hr", "inst:minagri:admin")).get("minagri");
            assertThat(g.templates()).containsExactly("local-hr", "local-admin");
            assertThat(g.includeSubgroups()).isTrue();
            assertThat(g.mayMapRole("inst:minagri:admin")).isFalse();
        }

        @Test
        void grantForGivesAnInstitutionsCeiling() {
            ResolvedGrant g = registry.grantFor("minagri");
            assertThat(g.templates()).containsExactlyInAnyOrder("local-admin", "local-hr");
            assertThat(g.has(Capability.MANAGE_CLIENTS)).isTrue();
            assertThat(registry.grantFor("mineduc").templates()).containsExactly("local-hr");
        }

        @ParameterizedTest
        @ValueSource(strings = {"MINAGRI", "min agri", "", "min/agri", "minagri\n", "min:agri"})
        void grantForRejectsAnInvalidSlug(String bad) {
            assertThatThrownBy(() -> registry.grantFor(bad)).isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining("is not a valid institution slug");
        }

        @Test
        void grantForRejectsNullAndAnInstitutionWithEveryTierWithheld() {
            assertThatThrownBy(() -> registry.grantFor(null))
                    .isInstanceOf(DelegatedPermissionConfigException.class).hasMessageStartingWith("None ");
            DelegatedPermissionRegistry r = DelegatedPermissionsParser.fromMap(Fixtures.edit(raw -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) raw.get("assignments");
                a.put("by_institution", Map.of("rdb", List.of()));
            }), "<test>");
            assertThatThrownBy(() -> r.grantFor("rdb")).isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessage("no delegated-permission templates are enabled for institution 'rdb'");
        }

        @Test
        void discoveryReportsDisabledTiersToo() {
            Map<String, List<String>> found = registry.grantRolesIn(List.of("inst:minagri:admin",
                    "inst:mineduc:admin", "unrelated"));
            assertThat(found.get("minagri")).containsExactly("local-admin");
            assertThat(found.get("mineduc")).containsExactly("local-admin");
            assertThat(found.keySet()).containsExactly("minagri", "mineduc");
            assertThat(registry.grantRolesIn(null)).isEmpty();
        }

        @Test
        void enabledTemplatesAndConfiguredInstitutions() {
            assertThat(registry.enabledTemplates("minagri")).extracting(PermissionTemplate::name)
                    .containsExactly("local-admin", "local-hr");
            assertThat(registry.enabledTemplates("mineduc")).extracting(PermissionTemplate::name)
                    .containsExactly("local-hr");
            assertThat(registry.configuredInstitutions()).containsExactly("mineduc");
        }
    }

    @Nested
    class GuardrailRules {
        @Test
        void cleanForInstitutionRolesOnly() {
            assertThat(registry.guardrail().violations(List.of("inst:minagri:admin", "employee"))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"admin", "super_admin", "manage-users"})
        void detectsAForbiddenRealmRole(String role) {
            List<GuardrailViolation> found = registry.guardrail().violations(List.of("inst:minagri:admin", role));
            assertThat(found).extracting(GuardrailViolation::role).containsExactly(role);
            assertThat(found.get(0).kind()).isEqualTo(GuardrailViolation.Kind.REALM_ROLE);
            assertThat(found.get(0).kind().wire()).isEqualTo("realm-role");
            assertThat(found.get(0).toString()).isEqualTo("realm role " + role);
        }

        @ParameterizedTest
        @ValueSource(strings = {"manage-users", "view-users", "query-users"})
        void anyRealmManagementClientRoleIsForbidden(String role) {
            List<GuardrailViolation> found = registry.guardrail().violations(List.of("inst:minagri:admin"),
                    Map.of("realm-management", List.of(role)));
            assertThat(found).hasSize(1);
            assertThat(found.get(0).kind().wire()).isEqualTo("client-role");
            assertThat(found.get(0).client()).isEqualTo("realm-management");
            assertThat(found.get(0).toString()).isEqualTo("realm-management:" + role);
        }

        @Test
        void otherClientsRolesAreNotForbidden() {
            assertThat(registry.guardrail().violations(List.of("inst:minagri:admin"),
                    Map.of("minagri-kubaka-web", List.of("editor")))).isEmpty();
        }

        @Test
        void violationsAreSortedAndDeduplicated() {
            List<GuardrailViolation> found = registry.guardrail().violations(
                    List.of("super_admin", "admin", "admin"),
                    Map.of("realm-management", List.of("view-users", "manage-users", "view-users")));
            assertThat(found).extracting(Object::toString).containsExactly("realm role admin",
                    "realm role super_admin", "realm-management:manage-users", "realm-management:view-users");
        }

        @Test
        void clientRolePatternsAreGlobs() {
            Guardrail g = new Guardrail(java.util.Set.of(), Map.of("broker", List.of("read-*")));
            assertThat(g.isForbiddenClientRole("broker", "read-token")).isTrue();
            assertThat(g.isForbiddenClientRole("broker", "write")).isFalse();
            assertThat(g.isForbiddenClientRole("other", "read-token")).isFalse();
            assertThat(g.violations(null, null)).isEmpty();
        }

        @Test
        void configCannotDisableTheGuardrail() {
            assertThatThrownBy(() -> Fixtures.build("guardrail",
                    "{\"forbidden_realm_roles\": [], \"forbidden_client_roles\": {}}"))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining("guardrail defines no forbidden roles");
            assertThatThrownBy(() -> Fixtures.build("guardrail", "null"))
                    .isInstanceOf(DelegatedPermissionConfigException.class);
        }

        @Test
        void guardrailShapeIsValidated() {
            assertThatThrownBy(() -> Fixtures.build("guardrail", "{\"forbidden_realm_roles\": \"admin\"}"))
                    .hasMessage("guardrail.forbidden_realm_roles must be a list of role names");
            assertThatThrownBy(() -> Fixtures.build("guardrail", "{\"forbidden_realm_roles\": [1]}"))
                    .hasMessage("guardrail.forbidden_realm_roles must be a list of role names");
            assertThatThrownBy(() -> Fixtures.build("guardrail",
                    "{\"forbidden_realm_roles\": [\"a\"], \"forbidden_client_roles\": [\"x\"]}"))
                    .hasMessageContaining("must be an object of clientId -> [role patterns]");
            assertThatThrownBy(() -> Fixtures.build("guardrail",
                    "{\"forbidden_client_roles\": {\"realm-management\": \"*\"}}"))
                    .hasMessage("guardrail.forbidden_client_roles['realm-management'] must be a list of role patterns");
            // client roles alone are enough to keep the guardrail on
            assertThatCode(() -> Fixtures.build("guardrail",
                    "{\"forbidden_client_roles\": {\"realm-management\": [\"*\"]}}")).doesNotThrowAnyException();
        }
    }

    @Nested
    class ConfigValidation {
        private DelegatedPermissionRegistry parse(Map<String, Object> raw) {
            return DelegatedPermissionsParser.fromMap(raw, "<test>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"allow\": [\"*\"]}", "{\"allow\": [\"inst:*\"]}", "{\"allow\": [\"admin\"]}",
                "{\"allow\": [\"inst:{slug}:*\", \"super_admin\"]}", "{\"allow\": [\"inst:\"]}",
                "{\"allow\": [\"inst:{slug}:\"]}"})
        void allowPatternsMustStayInTheNamespace(String roles) {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("roles", roles)))
                    .isInstanceOf(DelegatedPermissionConfigException.class);
        }

        @Test
        void namespaceMessageNamesThePattern() {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("roles", "{\"allow\": [\"*\"]}")))
                    .hasMessage("templates.local-admin.roles.allow['*'] must stay inside the institution namespace "
                            + "'inst:{slug}:*' — map-roles scoping depends on it");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"allow\": [\"*\"]}", "{\"allow\": [\"sso-admin-ui\"]}",
                "{\"allow\": [\"{slug}-*\", \"security-admin-console\"]}", "{\"allow\": [\"{slug}-\"]}"})
        void clientAllowPatternsMustStayInTheNamespace(String clients) {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("clients", clients)))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining("client namespace '{slug}-*'");
        }

        @Test
        void denyPatternsAreNotNamespaceRestricted() {
            assertThatCode(() -> parse(Fixtures.withTemplate("roles",
                    "{\"allow\": [\"inst:{slug}:*\"], \"deny\": [\"*admin*\"]}"))).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"admin", "inst:admin", "institution-{slug}-admin", "super_admin"})
        void grantRoleMustBeNamespacedAndPerInstitution(String grantRole) {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("grant_role", "\"" + grantRole + "\"")))
                    .isInstanceOf(DelegatedPermissionConfigException.class);
        }

        @Test
        void grantRoleMessages() {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("grant_role", "\"inst:admin\"")))
                    .hasMessage("templates.local-admin.grant_role must contain '{slug}' — a grant role that is not "
                            + "per-institution would apply everywhere");
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("grant_role", "\"x-{slug}\"")))
                    .hasMessage("templates.local-admin.grant_role must start with 'inst:{slug}:' (got 'x-{slug}')");
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("grant_role", "\"\"")))
                    .hasMessage("templates.local-admin.grant_role is required");
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("grant_role", "5")))
                    .hasMessage("templates.local-admin.grant_role is required");
        }

        @Test
        void aGrantRoleThatIsItselfForbiddenIsRefused() {
            Map<String, Object> raw = Fixtures.withTemplate("grant_role", "\"inst:{slug}:admin\"");
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) raw.get("guardrail");
            g.put("forbidden_realm_roles", List.of("inst:{slug}:admin"));
            assertThatThrownBy(() -> parse(raw)).hasMessage(
                    "templates.local-admin.grant_role 'inst:{slug}:admin' is a forbidden broad role");
        }

        @Test
        void anAllowPatternThatCouldReachAForbiddenRoleIsRefused() {
            Map<String, Object> raw = Fixtures.base();
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) raw.get("guardrail");
            g.put("forbidden_realm_roles", List.of("inst:probe:root", "admin"));
            assertThatThrownBy(() -> parse(raw)).hasMessage(
                    "templates.local-admin.roles.allow can match forbidden broad role(s) ['inst:probe:root']");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/", "/other", "/{slug}x", "{slug}"})
        void memberGroupMustBeTheInstitutionsGroup(String group) {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("members", "{\"group\": \"" + group + "\"}")))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining(".members.group must be '/{slug}' or a subgroup of it");
        }

        @Test
        void memberGroupMayBeASubgroupAndDefaultsToTheRoot() {
            DelegatedPermissionRegistry r = parse(Fixtures.withTemplate("members",
                    "{\"group\": \"/{slug}/hq\", \"include_subgroups\": false}"));
            assertThat(r.templates().get("local-admin").render("minagri").groupPath()).isEqualTo("/minagri/hq");
            r = parse(Fixtures.withTemplate("members", "{}"));
            assertThat(r.templates().get("local-admin").group()).isEqualTo("/{slug}");
            assertThat(r.templates().get("local-admin").includeSubgroups()).isTrue();
            r = parse(Fixtures.withTemplate("members", "{\"include_subgroups\": null}"));
            assertThat(r.templates().get("local-admin").includeSubgroups()).isFalse();
        }

        @Test
        void unknownCapabilityIsRejectedWithTheKnownSet() {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("capabilities", "[\"manage-everything\"]")))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining("view-members")
                    .hasMessage("templates.local-admin.capabilities: 'manage-everything' is not a valid Capability. "
                            + "Known capabilities: view-members, manage-members, manage-membership, map-roles, "
                            + "view-clients, manage-clients");
        }

        @Test
        void listFieldsMustBeListsOfStrings() {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("capabilities", "\"view-members\"")))
                    .hasMessage("templates.local-admin.capabilities must be a list of strings");
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("roles", "{\"allow\": [1]}")))
                    .hasMessage("templates.local-admin.roles.allow must be a list of strings");
            assertThatThrownBy(() -> parse(Fixtures.withTemplate("provision", "{\"roles\": \"x\"}")))
                    .hasMessage("templates.local-admin.provision.roles must be a list of strings");
        }

        @ParameterizedTest
        @ValueSource(strings = {"members", "roles", "clients", "provision"})
        void sectionsMustBeObjects(String section) {
            assertThatThrownBy(() -> parse(Fixtures.withTemplate(section, "[\"x\"]")))
                    .hasMessage("templates.local-admin." + section + " must be an object");
        }

        @Test
        void aTemplateMustBeAnObject() {
            Map<String, Object> raw = Fixtures.base();
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) raw.get("templates");
            t.put("local-admin", "nope");
            assertThatThrownBy(() -> parse(raw)).hasMessage("templates.local-admin must be an object");
        }

        @Test
        void unknownVersionIsRejected() {
            assertThatThrownBy(() -> Fixtures.build("version", "2"))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessage("unsupported template config version 2 (expected 1)");
            assertThatThrownBy(() -> Fixtures.build("version", "\"1\""))
                    .hasMessage("unsupported template config version '1' (expected 1)");
            assertThatThrownBy(() -> Fixtures.build("version", "null"))
                    .hasMessage("unsupported template config version None (expected 1)");
        }

        @Test
        void rootMustBeAnObjectWithTemplates() {
            assertThatThrownBy(() -> DelegatedPermissionsParser.fromMap(List.of(), "<t>"))
                    .hasMessage("template config must be a JSON object");
            assertThatThrownBy(() -> Fixtures.build("templates", "{}"))
                    .hasMessage("templates must be a non-empty object");
            assertThatThrownBy(() -> Fixtures.build("templates", "[]"))
                    .hasMessage("templates must be a non-empty object");
        }

        @Test
        void assignmentsMustReferenceKnownTemplates() {
            assertThatThrownBy(() -> Fixtures.build("assignments", "{\"default\": [\"does-not-exist\"]}"))
                    .hasMessage("assignments.default references unknown template(s) ['does-not-exist']");
            assertThatThrownBy(() -> Fixtures.build("assignments",
                    "{\"default\": [\"local-hr\"], \"by_institution\": {\"rdb\": [\"ghost\"]}}"))
                    .hasMessage("assignments.rdb references unknown template(s) ['ghost']");
        }

        @Test
        void assignmentsNeedADefault() {
            assertThatThrownBy(() -> Fixtures.build("assignments", "{\"default\": []}"))
                    .hasMessage("assignments.default must name at least one template");
            assertThatThrownBy(() -> Fixtures.build("assignments", "null"))
                    .hasMessage("assignments.default must name at least one template");
            assertThatThrownBy(() -> Fixtures.build("assignments", "[\"local-hr\"]"))
                    .hasMessage("assignments must be an object");
        }

        @Test
        void byInstitutionKeysMustBeSlugs() {
            assertThatThrownBy(() -> Fixtures.build("assignments",
                    "{\"default\": [\"local-admin\"], \"by_institution\": {\"MINEDUC\": [\"local-admin\"]}}"))
                    .hasMessage("assignments.by_institution key 'MINEDUC' is not a valid slug");
            assertThatThrownBy(() -> Fixtures.build("assignments",
                    "{\"default\": [\"local-admin\"], \"by_institution\": [\"x\"]}"))
                    .hasMessage("assignments.by_institution must be an object of slug -> [template]");
        }

        @Test
        void displayNameAndDescriptionDefaults() {
            DelegatedPermissionRegistry r = parse(Fixtures.withTemplate("description", "[\"line one\", \"line two\"]"));
            PermissionTemplate t = r.templates().get("local-admin");
            assertThat(t.description()).isEqualTo("line one\nline two");
            assertThat(t.displayName()).isEqualTo("Institution administrator");
            r = parse(Fixtures.withTemplate("display_name", "\"\""));
            assertThat(r.templates().get("local-admin").displayName()).isEqualTo("local-admin");
            assertThat(r.templates().get("local-admin").description()).isEmpty();
        }

        @Test
        void aMissingFileFailsClosed(@TempDir Path tmp) {
            assertThatThrownBy(() -> DelegatedPermissionsLoader.loadFrom(tmp.resolve("nope.json")))
                    .isInstanceOf(DelegatedPermissionConfigException.class).hasMessageContaining("cannot read");
        }

        @Test
        void malformedJsonFailsClosed(@TempDir Path tmp) throws Exception {
            Path p = tmp.resolve("broken.json");
            Files.writeString(p, "{not json");
            assertThatThrownBy(() -> DelegatedPermissionsLoader.loadFrom(p))
                    .isInstanceOf(DelegatedPermissionConfigException.class).hasMessageContaining("is not valid JSON");
        }

        @Test
        void locateFindsTheFirstExistingCandidate(@TempDir Path tmp) throws Exception {
            Path cfg = tmp.resolve("configs/delegated-permissions/templates.json");
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg, Fixtures.BASE_CONFIG);
            assertThat(DelegatedPermissionsLoader.locate(DelegatedPermissionsLoader.DEFAULT_CONFIG,
                    List.of(tmp.resolve("nowhere"), tmp))).isEqualTo(cfg);
            assertThat(DelegatedPermissionsLoader.locate(cfg.toString(), List.of())).isEqualTo(cfg);
            assertThatThrownBy(() -> DelegatedPermissionsLoader.locate("missing/x.json", List.of(tmp)))
                    .isInstanceOf(DelegatedPermissionConfigException.class)
                    .hasMessageContaining("Set DELEGATED_PERMISSIONS_CONFIG to its location.");
            assertThat(DelegatedPermissionsLoader.loadFrom(cfg).source()).isEqualTo(cfg.toString());
        }
    }

    @Nested
    class Describe {
        @Test
        void grantDescribeIsJsonSafeAndOrdered() {
            Map<String, Object> d = registry.grantFor("minagri").describe();
            assertThat(d.keySet()).containsExactly("institution", "templates", "capabilities", "members", "roles",
                    "clients");
            assertThat(d.get("capabilities")).isEqualTo(List.of("manage-clients", "manage-members",
                    "manage-membership", "map-roles", "view-clients", "view-members"));
            assertThat(d.get("members")).isEqualTo(Map.of("group", "/minagri", "include_subgroups", true));
        }

        @Test
        void registryDescribe() {
            Map<String, Object> d = registry.describe();
            assertThat(d.keySet()).containsExactly("version", "source", "guardrail", "assignments", "templates");
            assertThat(d.get("source")).isEqualTo("<test>");
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) d.get("guardrail");
            assertThat(g.get("forbidden_realm_roles")).isEqualTo(List.of("admin", "manage-users", "super_admin"));
            assertThat((List<?>) d.get("templates")).hasSize(3);
        }
    }

    @Nested
    class ShippedConfig {
        private final DelegatedPermissionRegistry shipped =
                DelegatedPermissionsLoader.loadFrom(TestRepo.path("configs/delegated-permissions/templates.json"));

        @Test
        void itLoads() {
            assertThat(shipped.templates()).isNotEmpty();
            assertThat(shipped.defaultTemplates()).isNotEmpty();
        }

        @Test
        void everyTemplateIsNamespaced() {
            for (PermissionTemplate t : shipped.templates().values()) {
                assertThat(t.grantRole()).startsWith("inst:{slug}:");
                t.roleAllow().forEach(p -> assertThat(p).startsWith("inst:{slug}:"));
                t.clientAllow().forEach(p -> assertThat(p).startsWith("{slug}-"));
            }
        }

        @Test
        void noTierCanAssignABroadRole() {
            for (PermissionTemplate t : shipped.templates().values()) {
                ResolvedGrant g = t.render("minagri");
                for (String role : List.of("admin", "super_admin", "system_admin", "realm_admin", "manage-users",
                        "manage-realm", "realm-management")) {
                    assertThat(g.mayMapRole(role)).as(t.name() + " " + role).isFalse();
                }
            }
        }

        @Test
        void noTierCanManageAControlPlaneClient() {
            for (PermissionTemplate t : shipped.templates().values()) {
                ResolvedGrant g = t.render("minagri");
                for (String c : List.of("sso-admin-ui", "sso-client", "security-admin-console")) {
                    assertThat(g.mayViewClient(c)).isFalse();
                    assertThat(g.mayManageClient(c)).isFalse();
                }
            }
        }

        @Test
        void noTierCanReachAnotherInstitution() {
            for (PermissionTemplate t : shipped.templates().values()) {
                ResolvedGrant g = t.render("minagri");
                assertThat(g.mayMapRole("inst:minisante:staff")).isFalse();
                assertThat(g.mayManageClient("minisante-hmis")).isFalse();
                assertThat(g.coversGroupPath("/minisante")).isFalse();
            }
        }

        @Test
        void theAdminTierCannotAppointAnotherAdmin() {
            assertThat(shipped.templates().get("institution-admin").render("minagri").mayMapRole("inst:minagri:admin"))
                    .isFalse();
        }

        @Test
        void theGuardrailForbidsRealmManagementEntirely() {
            assertThat(shipped.guardrail().forbiddenClientRoles().get("realm-management")).containsExactly("*");
            for (String role : List.of("manage-users", "view-users", "query-users", "manage-realm")) {
                assertThat(shipped.guardrail().isForbiddenClientRole("realm-management", role)).isTrue();
            }
        }

        @Test
        void theGuardrailForbidsThePlatformRoles() {
            for (String role : List.of("admin", "super_admin", "system_admin", "realm_admin")) {
                assertThat(shipped.guardrail().isForbiddenRealmRole(role)).isTrue();
            }
        }

        @Test
        void theGuardrailNamesManageUsersAsARealmRoleToo() {
            for (String role : List.of("manage-users", "manage-realm", "manage-authorization")) {
                assertThat(shipped.guardrail().isForbiddenRealmRole(role)).isTrue();
            }
        }
    }
}
