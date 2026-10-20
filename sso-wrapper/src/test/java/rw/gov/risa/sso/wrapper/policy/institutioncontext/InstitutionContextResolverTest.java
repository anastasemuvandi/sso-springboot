package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.Guardrail;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;

class InstitutionContextResolverTest {

    static ResolvedGrant grant(String slug, Set<Capability> caps) {
        return new ResolvedGrant(slug, List.of("institution-admin"), caps, "/" + slug, true,
                List.of("inst:" + slug + ":*"), List.of("inst:" + slug + ":admin"),
                List.of(slug + "-*"), List.of(slug + "-secret-*"));
    }

    static final Set<Capability> ALL = EnumSet.allOf(Capability.class);

    /** Fake registry: role inst:<slug>:<anything> grants the full template for slug. */
    static final class FakeLookup implements GrantLookup {
        final Set<String> known;
        final Set<Capability> caps;

        FakeLookup(Set<String> known, Set<Capability> caps) {
            this.known = known;
            this.caps = caps;
        }

        @Override
        public Map<String, ResolvedGrant> resolveCaller(Collection<String> realmRoles) {
            Map<String, ResolvedGrant> out = new LinkedHashMap<>();
            for (String r : realmRoles) {
                String[] p = r.split(":", 3);
                if (p.length == 3 && p[0].equals("inst") && known.contains(p[1])) {
                    out.put(p[1], grant(p[1], caps));
                }
            }
            return out;
        }

        @Override
        public ResolvedGrant grantFor(String slug) {
            if (!known.contains(slug)) {
                throw new DelegatedPermissionConfigException("No delegated templates for institution '" + slug + "'.");
            }
            return grant(slug, caps);
        }

        @Override
        public Guardrail guardrail() {
            return new Guardrail(Set.of("realm-admin", "manage-users", "admin", "super_admin"),
                    Map.of("realm-management", List.of("*")));
        }
    }

    static final FakeLookup LOOKUP = new FakeLookup(Set.of("minagri", "minisante", "rra"), ALL);

    static InstitutionContextResolver resolver() {
        return new InstitutionContextResolver(LOOKUP, InstitutionContextSettings.defaults());
    }

    static CallerIdentity caller(String... roles) {
        return CallerIdentity.of("alice", "u-1", List.of(roles));
    }

    static Refusal refusal(Resolution<?> r) {
        assertThat(r).isInstanceOf(Resolution.Refused.class);
        return ((Resolution.Refused<?>) r).refusal();
    }

    @Nested
    class InstitutionAdmin {

        @Test
        void singleInstitutionIsPinned() {
            InstitutionContext ctx = resolver().resolveInstitutionAdmin(caller("inst:minagri:admin"), null).orThrow();
            assertThat(ctx.institution()).isEqualTo("minagri");
            assertThat(ctx.administered()).containsExactly("minagri");
            assertThat(ctx.platformAdmin()).isFalse();
            assertThat(ctx.rolePrefix()).isEqualTo("inst:minagri:");
            assertThat(ctx.clientPrefix()).isEqualTo("minagri-");
            assertThat(ctx.groupPath()).isEqualTo("/minagri");
            assertThat(ctx.actor()).isEqualTo("alice");
            assertThat(ctx.capabilities()).containsExactly("manage-clients", "manage-members",
                    "manage-membership", "map-roles", "view-clients", "view-members");
        }

        @Test
        void namingOwnInstitutionWorks() {
            var ctx = resolver().resolveInstitutionAdmin(caller("inst:minagri:admin"), "minagri").orThrow();
            assertThat(ctx.institution()).isEqualTo("minagri");
        }

        @Test
        void namingAnotherInstitutionIs403() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("inst:minagri:admin"), "minisante"));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo(Refusal.Code.INSTITUTION_NOT_ADMINISTERED);
            assertThat(r.message()).contains("'minisante'").contains("['minagri']");
        }

        @Test
        void noGrantIs403() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("offline_access"), null));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code().wire()).isEqualTo("not_institution_admin");
        }

        @Test
        void noGrantNamingInstitutionIs403WithNone() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("offline_access"), "minagri"));
            assertThat(r.code()).isEqualTo(Refusal.Code.INSTITUTION_NOT_ADMINISTERED);
            assertThat(r.message()).contains("Your institutions: none.");
        }

        @Test
        void multipleInstitutionsRequireAChoice() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(
                    caller("inst:minisante:admin", "inst:minagri:admin"), null));
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo(Refusal.Code.MULTIPLE_INSTITUTIONS);
            assertThat(r.message()).contains("['minagri', 'minisante']");
        }

        @Test
        void multipleInstitutionsWithChoiceResolve() {
            var ctx = resolver().resolveInstitutionAdmin(
                    caller("inst:minisante:admin", "inst:minagri:admin"), "minisante").orThrow();
            assertThat(ctx.institution()).isEqualTo("minisante");
            assertThat(ctx.administered()).containsExactly("minagri", "minisante");
        }

        @Test
        void platformAdminWithoutInstitutionIs400() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("super_admin"), null));
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo(Refusal.Code.INSTITUTION_REQUIRED);
        }

        @Test
        void platformAdminMayActForAnyInstitutionWithFullTemplateGrant() {
            for (String role : List.of("super_admin", "admin", "system_admin")) {
                var ctx = resolver().resolveInstitutionAdmin(caller(role), "rra").orThrow();
                assertThat(ctx.institution()).isEqualTo("rra");
                assertThat(ctx.platformAdmin()).isTrue();
                assertThat(ctx.grant().institution()).isEqualTo("rra");
            }
        }

        @Test
        void platformAdminNamingUnknownInstitutionIs400() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("super_admin"), "nope"));
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo(Refusal.Code.UNKNOWN_INSTITUTION);
            assertThat(r.message()).contains("nope");
        }

        @Test
        void platformAdminIsExemptFromGuardrailAndClaimCheck() {
            CallerIdentity c = caller("admin", "inst:minagri:admin")
                    .withClientRoles(Map.of("realm-management", List.of("manage-users")))
                    .withInstitutionClaim("minisante");
            Resolution<InstitutionContext> r = resolver().resolveInstitutionAdmin(c, null);
            assertThat(r.orThrow().institution()).isEqualTo("minagri");
            assertThat(r.warnings()).isEmpty();
        }

        @Test
        void unavailableConfigIs503() {
            var res = new InstitutionContextResolver(() -> {
                throw new DelegatedPermissionConfigException("templates.json missing");
            }, InstitutionContextSettings.defaults());
            Refusal r = refusal(res.resolveInstitutionAdmin(caller("inst:minagri:admin"), null));
            assertThat(r.status()).isEqualTo(503);
            assertThat(r.code().wire()).isEqualTo("delegated_config_unavailable");
            assertThat(r.message()).contains("templates.json missing");
            Refusal d = refusal(res.resolveDirectoryScope(caller("super_admin"), null));
            assertThat(d.status()).isEqualTo(503);
        }

        @Test
        void orThrowRaisesRefusal() {
            assertThatThrownBy(() -> resolver().resolveInstitutionAdmin(caller(), null).orThrow())
                    .isInstanceOf(InstitutionRefusedException.class)
                    .satisfies(e -> assertThat(((InstitutionRefusedException) e).refusal().status()).isEqualTo(403));
        }

        @Test
        void unknownSlugRoleIsNotAGrant() {
            Refusal r = refusal(resolver().resolveInstitutionAdmin(caller("inst:ghost:admin"), null));
            assertThat(r.code()).isEqualTo(Refusal.Code.NOT_INSTITUTION_ADMIN);
        }
    }

    @Nested
    class Guardrails {

        @Test
        void forbiddenRealmRoleAlongsideGrantIs403() {
            Resolution<InstitutionContext> r =
                    resolver().resolveInstitutionAdmin(caller("inst:minagri:admin", "realm-admin"), null);
            Refusal f = refusal(r);
            assertThat(f.status()).isEqualTo(403);
            assertThat(f.code()).isEqualTo(Refusal.Code.GUARDRAIL_VIOLATION);
            assertThat(f.message()).contains("realm role realm-admin");
            assertThat(r.warnings()).singleElement().asString().contains("guardrail violation");
        }

        @Test
        void anyRealmManagementClientRoleIs403() {
            CallerIdentity c = caller("inst:minagri:admin")
                    .withClientRoles(Map.of("realm-management", List.of("view-users")));
            Refusal f = refusal(resolver().resolveInstitutionAdmin(c, null));
            assertThat(f.code()).isEqualTo(Refusal.Code.GUARDRAIL_VIOLATION);
            assertThat(f.message()).contains("realm-management:view-users");
        }

        @Test
        void otherClientRolesAreFine() {
            CallerIdentity c = caller("inst:minagri:admin")
                    .withClientRoles(Map.of("minagri-kubaka-web", List.of("editor")));
            assertThat(resolver().resolveInstitutionAdmin(c, null).isRefused()).isFalse();
        }

        @Test
        void callerWithoutGrantIsNotLecturedAboutGuardrail() {
            Refusal f = refusal(resolver().resolveInstitutionAdmin(caller("realm-admin"), null));
            assertThat(f.code()).isEqualTo(Refusal.Code.NOT_INSTITUTION_ADMIN);
        }

        @Test
        void guardrailNotEnforcedOnlyWarns() {
            var res = new InstitutionContextResolver(LOOKUP, new InstitutionContextSettings(false, true));
            Resolution<InstitutionContext> r = res.resolveInstitutionAdmin(caller("inst:minagri:admin", "manage-users"), null);
            assertThat(r.orThrow().institution()).isEqualTo("minagri");
            assertThat(r.warnings()).hasSize(1);
        }

        @Test
        void violationsHelperListsRealmThenClient() {
            CallerIdentity c = caller("admin", "realm-admin")
                    .withClientRoles(Map.of("realm-management", List.of("manage-users", "impersonation")));
            assertThat(InstitutionContextResolver.guardrailViolations(LOOKUP, c)).extracting(Object::toString)
                    .containsExactly("realm role admin", "realm role realm-admin",
                            "realm-management:impersonation", "realm-management:manage-users");
        }
    }

    @Nested
    class ClaimCrossCheck {

        @Test
        void matchingOrMissingClaimPasses() {
            assertThat(resolver().resolveInstitutionAdmin(
                    caller("inst:minagri:admin").withInstitutionClaim("minagri"), null).isRefused()).isFalse();
            assertThat(resolver().resolveInstitutionAdmin(
                    caller("inst:minagri:admin").withInstitutionClaim(""), null).isRefused()).isFalse();
        }

        @Test
        void contradictingClaimIs403() {
            Resolution<InstitutionContext> r = resolver().resolveInstitutionAdmin(
                    caller("inst:minagri:admin").withInstitutionClaim("minisante"), null);
            Refusal f = refusal(r);
            assertThat(f.code()).isEqualTo(Refusal.Code.INSTITUTION_CLAIM_MISMATCH);
            assertThat(f.message()).contains("'minisante'").contains("'minagri'");
            assertThat(r.warnings()).singleElement().asString().contains("claim mismatch");
        }

        @Test
        void crosscheckOffOnlyWarns() {
            var res = new InstitutionContextResolver(LOOKUP, new InstitutionContextSettings(true, false));
            Resolution<InstitutionContext> r = res.resolveInstitutionAdmin(
                    caller("inst:minagri:admin").withInstitutionClaim("minisante"), null);
            assertThat(r.isRefused()).isFalse();
            assertThat(r.warnings()).hasSize(1);
        }

        @Test
        void claimRule() {
            assertThat(InstitutionContextResolver.claimContradicts(null, "a")).isFalse();
            assertThat(InstitutionContextResolver.claimContradicts("", "a")).isFalse();
            assertThat(InstitutionContextResolver.claimContradicts("a", "a")).isFalse();
            assertThat(InstitutionContextResolver.claimContradicts("b", "a")).isTrue();
        }
    }

    @Nested
    class ContextGuards {

        InstitutionContext ctx(Set<Capability> caps) {
            var res = new InstitutionContextResolver(new FakeLookup(Set.of("minagri"), caps),
                    InstitutionContextSettings.defaults());
            return res.resolveInstitutionAdmin(caller("inst:minagri:admin"), null).orThrow();
        }

        @Test
        void capabilityCheck() {
            InstitutionContext c = ctx(EnumSet.of(Capability.VIEW_MEMBERS));
            assertThat(c.checkCapability(Capability.VIEW_MEMBERS)).isEmpty();
            Refusal r = c.checkCapability(Capability.MANAGE_MEMBERS).orElseThrow();
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo(Refusal.Code.CAPABILITY_DENIED);
            assertThat(r.message()).contains("'manage-members'").contains("['view-members']");
            assertThatThrownBy(() -> c.assertCapability(Capability.MAP_ROLES))
                    .isInstanceOf(InstitutionRefusedException.class);
        }

        @Test
        void requireCapability() {
            var res = new InstitutionContextResolver(new FakeLookup(Set.of("minagri"), EnumSet.of(Capability.VIEW_MEMBERS)),
                    InstitutionContextSettings.defaults());
            assertThat(res.requireCapability(caller("inst:minagri:admin"), null, Capability.VIEW_MEMBERS).isRefused())
                    .isFalse();
            assertThat(refusal(res.requireCapability(caller("inst:minagri:admin"), null, Capability.MAP_ROLES)).code())
                    .isEqualTo(Refusal.Code.CAPABILITY_DENIED);
        }

        @Test
        void roleNamespace() {
            InstitutionContext c = ctx(ALL);
            assertThat(c.checkRoleInNamespace("inst:minagri:hr")).isEmpty();
            Refusal deny = c.checkRoleInNamespace("inst:minagri:admin").orElseThrow();
            assertThat(deny.code()).isEqualTo(Refusal.Code.ROLE_NOT_ASSIGNABLE);
            assertThat(deny.message()).contains("excluding ['inst:minagri:admin']");
            assertThat(c.checkRoleInNamespace("manage-users")).isPresent();
            assertThat(c.checkRoleInNamespace("inst:minisante:hr")).isPresent();
            assertThat(c.checkRoleInNamespace("super_admin")).isPresent();
        }

        @Test
        void roleNamespaceNeedsMapRoles() {
            InstitutionContext c = ctx(EnumSet.of(Capability.VIEW_MEMBERS));
            assertThat(c.checkRoleInNamespace("inst:minagri:hr").orElseThrow().code())
                    .isEqualTo(Refusal.Code.CAPABILITY_DENIED);
        }

        @Test
        void ownClientInScope() {
            InstitutionContext c = ctx(ALL);
            assertThat(c.checkClientInScope("minagri-kubaka-web", null)).isEmpty();
            assertThat(c.checkClientInScope("minagri-kubaka-web", null, Capability.VIEW_CLIENTS)).isEmpty();
        }

        @Test
        void denyPatternBeatsOwnership() {
            InstitutionContext c = ctx(ALL);
            assertThat(c.checkClientInScope("minagri-secret-x", ClientScope.forInstitution("minagri-secret-x", "minagri")))
                    .isPresent();
        }

        @Test
        void otherInstitutionsClientNamesOwner() {
            InstitutionContext c = ctx(ALL);
            Refusal r = c.checkClientInScope("minisante-app", ClientScope.forInstitution("minisante-app", "minisante"))
                    .orElseThrow();
            assertThat(r.code()).isEqualTo(Refusal.Code.CLIENT_OUT_OF_SCOPE);
            assertThat(r.message()).contains("belongs to institution 'minisante'");
        }

        @Test
        void crossCuttingSharedClient() {
            InstitutionContext c = ctx(ALL);
            ClientScope shared = ClientScope.forSelected("irembo", List.of("minagri", "rra"));
            ClientScope notShared = ClientScope.forSelected("irembo", List.of("rra"));
            ClientScope everyone = ClientScope.forAllInstitutions("irembo");
            assertThat(c.shares(shared)).isTrue();
            assertThat(c.shares(everyone)).isTrue();
            assertThat(c.shares(notShared)).isFalse();
            assertThat(c.shares(null)).isFalse();
            assertThat(c.checkClientInScope("irembo", shared)).isEmpty();
            Refusal r = c.checkClientInScope("irembo", notShared).orElseThrow();
            assertThat(r.message()).contains("cross-cutting client whose institutions do not include 'minagri'");
            assertThat(c.checkClientInScope("irembo", null).orElseThrow().message()).endsWith("(['minagri-*']).");
        }

        @Test
        void ownedClientIsNeverShared() {
            InstitutionContext c = ctx(ALL);
            assertThat(c.shares(ClientScope.forInstitution("minagri-x", "minagri"))).isFalse();
        }

        @Test
        void clientScopeNeedsCapability() {
            InstitutionContext c = ctx(EnumSet.of(Capability.VIEW_CLIENTS));
            assertThat(c.checkClientInScope("minagri-a", null).orElseThrow().code())
                    .isEqualTo(Refusal.Code.CAPABILITY_DENIED);
            assertThat(c.checkClientInScope("minagri-a", null, Capability.VIEW_CLIENTS)).isEmpty();
        }
    }

    @Nested
    class Directory {

        @Test
        void platformAdminSeesEverythingOrNamed() {
            DirectoryScope all = resolver().resolveDirectoryScope(caller("super_admin"), null).orThrow();
            assertThat(all.institution()).isNull();
            assertThat(all.isScoped()).isFalse();
            assertThat(all.platformAdmin()).isTrue();
            DirectoryScope one = resolver().resolveDirectoryScope(caller("admin"), "  MINAGRI ").orThrow();
            assertThat(one.institution()).isEqualTo("minagri");
            assertThat(resolver().resolveDirectoryScope(caller("admin"), "  ").orThrow().institution()).isNull();
        }

        @Test
        void delegatedAdminIsPinned() {
            DirectoryScope s = resolver().resolveDirectoryScope(caller("inst:minagri:admin"), null).orThrow();
            assertThat(s.institution()).isEqualTo("minagri");
            assertThat(s.isScoped()).isTrue();
            assertThat(resolver().resolveDirectoryScope(caller("inst:minagri:admin"), "MinAgri").orThrow()
                    .institution()).isEqualTo("minagri");
        }

        @Test
        void delegatedAdminNamingOtherIs403() {
            Refusal r = refusal(resolver().resolveDirectoryScope(caller("inst:minagri:admin"), "rra"));
            assertThat(r.code()).isEqualTo(Refusal.Code.INSTITUTION_NOT_ADMINISTERED);
        }

        @Test
        void nobodyIs403() {
            Refusal r = refusal(resolver().resolveDirectoryScope(caller(), null));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.message()).contains("no employee directory");
        }

        @Test
        void multipleIs400() {
            Refusal r = refusal(resolver().resolveDirectoryScope(caller("inst:minagri:a", "inst:rra:a"), null));
            assertThat(r.status()).isEqualTo(400);
        }

        @Test
        void guardrailAndClaimApply() {
            assertThat(refusal(resolver().resolveDirectoryScope(caller("inst:minagri:a", "realm-admin"), null)).code())
                    .isEqualTo(Refusal.Code.GUARDRAIL_VIOLATION);
            assertThat(refusal(resolver().resolveDirectoryScope(
                    caller("inst:minagri:a").withInstitutionClaim("rra"), null)).code())
                    .isEqualTo(Refusal.Code.INSTITUTION_CLAIM_MISMATCH);
        }

        @Test
        void needsViewMembers() {
            var res = new InstitutionContextResolver(new FakeLookup(Set.of("minagri"), EnumSet.of(Capability.MAP_ROLES)),
                    InstitutionContextSettings.defaults());
            Refusal r = refusal(res.resolveDirectoryScope(caller("inst:minagri:admin"), null));
            assertThat(r.code()).isEqualTo(Refusal.Code.CAPABILITY_DENIED);
            assertThat(r.message()).contains("'view-members'");
        }
    }
}
