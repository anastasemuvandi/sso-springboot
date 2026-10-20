package rw.gov.risa.sso.wrapper.policy.clientscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes.ATTR_CROSS_CUTTING;
import static rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes.ATTR_INSTITUTIONS;
import static rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes.ATTR_OWNER;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of tests/e2e/test_client_scope.py (pure half) plus extra edge cases. */
class ClientScopeTest {

    static final List<String> SLUGS = List.of("minagri", "minisante", "rdb");

    static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    class ParseIsTolerant {

        @Test
        void anUnmarkedClientWithNoPrefixIsAPlatformClient() {
            ClientScope scope = ClientScope.parse("sso-admin-ui", null, SLUGS);
            assertThat(scope.kind()).isEqualTo(ClientScopeKind.PLATFORM);
            assertThat(scope.isScoped()).isFalse();
            assertThat(SLUGS).noneMatch(scope::covers);
        }

        @Test
        void aSlugPrefixedClientIsOwnedByThatInstitution() {
            ClientScope scope = ClientScope.parse("minagri-kubaka-web", Map.of(), SLUGS);
            assertThat(scope.kind()).isEqualTo(ClientScopeKind.INSTITUTION);
            assertThat(scope.owner()).isEqualTo("minagri");
            assertThat(scope.inferred()).isTrue();
            assertThat(scope.covers("minagri")).isTrue();
            assertThat(scope.covers("rdb")).isFalse();
        }

        @Test
        void anExplicitOwnerAttributeBeatsThePrefix() {
            ClientScope scope = ClientScope.parse("minagri-shared", attrs(ATTR_OWNER, "rdb"), SLUGS);
            assertThat(scope.owner()).isEqualTo("rdb");
            assertThat(scope.inferred()).isFalse();
        }

        @Test
        void missingAttributesIsNotAnError() {
            assertThat(ClientScope.parse("x", null).kind()).isEqualTo(ClientScopeKind.PLATFORM);
        }

        @Test
        void allInstitutionsIsReadAsDynamic() {
            ClientScope scope = ClientScope.parse("national-payroll",
                    attrs(ATTR_CROSS_CUTTING, "true", ATTR_INSTITUTIONS, "*"), SLUGS);
            assertThat(scope.allInstitutions()).isTrue();
            assertThat(scope.institutions()).isEmpty();
            assertThat(scope.covers("brand-new-ministry")).isTrue();
            assertThat(scope.kind()).isEqualTo(ClientScopeKind.CROSS_CUTTING);
        }

        static Stream<Arguments> lenientLists() {
            return Stream.of(
                    Arguments.of("minagri##rdb", List.of("minagri", "rdb")),
                    Arguments.of("minagri, rdb", List.of("minagri", "rdb")),
                    Arguments.of("  minagri  ##  rdb ", List.of("minagri", "rdb")),
                    Arguments.of("minagri##minagri", List.of("minagri")),
                    Arguments.of("##,,minagri##", List.of("minagri")));
        }

        @ParameterizedTest
        @MethodSource("lenientLists")
        void aSelectedListIsReadLeniently(String raw, List<String> expected) {
            ClientScope scope = ClientScope.parse("c", attrs(ATTR_CROSS_CUTTING, "true", ATTR_INSTITUTIONS, raw), SLUGS);
            assertThat(scope.institutions()).isEqualTo(expected);
        }

        @Test
        void crossCuttingWithAnEmptyListCoversNobody() {
            ClientScope scope = ClientScope.parse("c", attrs(ATTR_CROSS_CUTTING, "true", ATTR_INSTITUTIONS, ""), SLUGS);
            assertThat(scope.crossCutting()).isTrue();
            assertThat(scope.allInstitutions()).isFalse();
            assertThat(scope.coversNobody()).isTrue();
            assertThat(SLUGS).noneMatch(scope::covers);
        }

        @Test
        void crossCuttingWithNoInstitutionsKeyCoversNobody() {
            ClientScope scope = ClientScope.parse("minagri-x", attrs(ATTR_CROSS_CUTTING, "true"), SLUGS);
            assertThat(scope.coversNobody()).isTrue();
            assertThat(scope.owner()).isNull();
        }

        @Test
        void anUnknownSlugInTheScopeIsKeptNotErased() {
            ClientScope scope = ClientScope.parse("c",
                    attrs(ATTR_CROSS_CUTTING, "true", ATTR_INSTITUTIONS, "gone-ministry"), SLUGS);
            assertThat(scope.institutions()).containsExactly("gone-ministry");
        }

        @Test
        void anUnknownOwnerIsKeptAsWritten() {
            ClientScope scope = ClientScope.parse("c", attrs(ATTR_OWNER, " gone "), SLUGS);
            assertThat(scope.owner()).isEqualTo("gone");
            assertThat(scope.inferred()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "1", "yes", "on", " On "})
        void theCrossCuttingFlagIsReadLoosely(String value) {
            ClientScope scope = ClientScope.parse("c", attrs(ATTR_CROSS_CUTTING, value, ATTR_INSTITUTIONS, "*"), SLUGS);
            assertThat(scope.crossCutting()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "false", "no", "0", "  "})
        void anythingElseIsNotCrossCutting(String value) {
            ClientScope scope = ClientScope.parse("minagri-x", attrs(ATTR_CROSS_CUTTING, value), SLUGS);
            assertThat(scope.crossCutting()).isFalse();
            assertThat(scope.owner()).isEqualTo("minagri");
        }

        @Test
        void listValuedAttributesAreTolerated() {
            Map<String, Object> listy = Map.of(ATTR_CROSS_CUTTING, List.of("true"), ATTR_INSTITUTIONS, List.of("rdb"));
            ClientScope scope = ClientScope.parse("c", listy, SLUGS);
            assertThat(scope.institutions()).containsExactly("rdb");
        }

        @Test
        void nonStringValuesAreTreatedAsAbsent() {
            Map<String, Object> odd = Map.of(ATTR_CROSS_CUTTING, 42, ATTR_OWNER, 7);
            assertThat(ClientScope.parse("c", odd, SLUGS).kind()).isEqualTo(ClientScopeKind.PLATFORM);
        }
    }

    @Nested
    class PrefixInference {

        @Test
        void theLongestMatchingSlugWins() {
            assertThat(ClientScopes.ownerFromClientId("local-govt-portal", List.of("local", "local-govt")))
                    .isEqualTo("local-govt");
            assertThat(ClientScopes.ownerFromClientId("local-govt-portal", List.of("local-govt", "local")))
                    .isEqualTo("local-govt");
        }

        @Test
        void anUnknownPrefixInfersNothing() {
            assertThat(ClientScopes.ownerFromClientId("kubaka-web", SLUGS)).isNull();
        }

        @Test
        void theSlugAloneIsNotAClientOfThatInstitution() {
            assertThat(ClientScopes.ownerFromClientId("minagri", SLUGS)).isNull();
        }

        @Test
        void blankInputsInferNothing() {
            assertThat(ClientScopes.ownerFromClientId("", SLUGS)).isNull();
            assertThat(ClientScopes.ownerFromClientId(null, SLUGS)).isNull();
            assertThat(ClientScopes.ownerFromClientId("minagri-x", null)).isNull();
            assertThat(ClientScopes.ownerFromClientId("-x", List.of(""))).isNull();
        }
    }

    @Nested
    class AttributeRoundTrip {

        static Stream<ClientScope> scopes() {
            return Stream.of(
                    ClientScope.forInstitution("minagri-portal", "minagri"),
                    ClientScope.forAllInstitutions("national-payroll"),
                    ClientScope.forSelected("shared", List.of("minagri", "rdb")),
                    ClientScope.platform("sso-admin-ui"));
        }

        @ParameterizedTest
        @MethodSource("scopes")
        void whatIsWrittenIsWhatIsRead(ClientScope scope) {
            ClientScope again = ClientScope.parse(scope.clientId(), scope.toAttributes(), SLUGS);
            assertThat(again.describe()).isEqualTo(scope.describe());
        }

        @Test
        void everyOwnedKeyIsWrittenEvenWhenEmpty() {
            Map<String, String> a = ClientScope.forInstitution("c", "minagri").toAttributes();
            assertThat(a.keySet()).containsExactlyInAnyOrder(ATTR_OWNER, ATTR_CROSS_CUTTING, ATTR_INSTITUTIONS);
            assertThat(a.get(ATTR_CROSS_CUTTING)).isEmpty();
            assertThat(a.get(ATTR_INSTITUTIONS)).isEmpty();
            assertThat(ClientScope.platform("c").toAttributes().values()).allMatch(String::isEmpty);
        }

        @Test
        void switchingToCrossCuttingClearsTheOwner() {
            Map<String, String> after = new HashMap<>(ClientScope.forInstitution("c", "minagri").toAttributes());
            after.putAll(ClientScope.forAllInstitutions("c").toAttributes());
            assertThat(after.get(ATTR_OWNER)).isEmpty();
            assertThat(ClientScope.parse("c", after, SLUGS).owner()).isNull();
        }

        @Test
        void aSelectedListUsesKeycloaksOwnSeparator() {
            assertThat(ClientScope.forSelected("c", List.of("minagri", "rdb")).toAttributes().get(ATTR_INSTITUTIONS))
                    .isEqualTo("minagri##rdb");
        }

        @Test
        void describeHasTheWireKeys() {
            Map<String, Object> d = ClientScope.forSelected("c", List.of("rdb")).describe();
            assertThat(d).containsOnlyKeys("client_id", "kind", "owner_institution", "cross_cutting",
                    "all_institutions", "institutions", "inferred");
            assertThat(d.get("kind")).isEqualTo("cross-cutting");
        }

        @Test
        void anInferredOwnerIsWrittenExplicitly() {
            ClientScope inferred = ClientScope.parse("minagri-x", null, SLUGS);
            assertThat(inferred.toAttributes().get(ATTR_OWNER)).isEqualTo("minagri");
        }
    }

    @Nested
    class RequestedScopeIsStrict {

        @Test
        void aCrossCuttingClientMustNameAnInstitution() {
            assertThatThrownBy(() -> ClientScope.requested("c", true, null, null, SLUGS))
                    .isInstanceOf(ClientScopeException.class)
                    .hasMessageContaining("cannot be saved with no institution");
            assertThatThrownBy(() -> ClientScope.requested("c", true, List.of(), null, SLUGS))
                    .isInstanceOf(ClientScopeException.class);
            assertThatThrownBy(() -> ClientScope.requested("c", true, List.of("  "), null, SLUGS))
                    .isInstanceOf(ClientScopeException.class);
        }

        @Test
        void allIsAcceptedAndStaysDynamic() {
            ClientScope scope = ClientScope.requested("c", true, List.of("*"), null, SLUGS);
            assertThat(scope.allInstitutions()).isTrue();
            assertThat(scope.institutions()).isEmpty();
        }

        @Test
        void aSelectionIsAcceptedInOrder() {
            ClientScope scope = ClientScope.requested("c", true, List.of("rdb", "minagri", "rdb"), null, SLUGS);
            assertThat(scope.institutions()).containsExactly("rdb", "minagri");
        }

        @Test
        void ownerAndCrossCuttingTogetherIsAContradiction() {
            assertThatThrownBy(() -> ClientScope.requested("c", true, List.of("rdb"), "minagri", SLUGS))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("not both");
        }

        @Test
        void institutionsWithoutCrossCuttingIsRefused() {
            assertThatThrownBy(() -> ClientScope.requested("c", false, List.of("rdb"), null, SLUGS))
                    .isInstanceOf(ClientScopeException.class)
                    .hasMessageContaining("only be set on a cross-cutting");
        }

        @Test
        void allCannotBeMixedWithASelection() {
            assertThatThrownBy(() -> ClientScope.requested("c", true, List.of("*", "rdb"), null, SLUGS))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("cannot mix");
        }

        @ParameterizedTest
        @ValueSource(strings = {"nope", "MINAGRI", "min agri", "x"})
        void anInstitutionOutsideTheRegistryIsRefused(String slug) {
            assertThatThrownBy(() -> ClientScope.requested("c", true, List.of(slug), null, SLUGS))
                    .isInstanceOf(ClientScopeException.class);
        }

        @Test
        void anInvalidOwnerSlugIsRefused() {
            assertThatThrownBy(() -> ClientScope.requested("c", false, null, "Min Agri", SLUGS))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("not a valid institution slug");
            assertThatThrownBy(() -> ClientScope.requested("c", false, null, "nope", SLUGS))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("not an institution in the registry");
        }

        @Test
        void anEmptyRegistryRefusesRatherThanAcceptingAnything() {
            assertThatThrownBy(() -> ClientScope.requested("c", false, null, "minagri", List.of()))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("registry is empty");
        }

        @Test
        void noScopeAtAllIsAPlatformClient() {
            assertThat(ClientScope.requested("sso-client", false, null, null, SLUGS).kind())
                    .isEqualTo(ClientScopeKind.PLATFORM);
            assertThat(ClientScope.requested("sso-client", false, null, "  ", SLUGS).kind())
                    .isEqualTo(ClientScopeKind.PLATFORM);
        }

        @Test
        void anOwnerIsAccepted() {
            ClientScope s = ClientScope.requested("minagri-x", false, null, " minagri ", SLUGS);
            assertThat(s.owner()).isEqualTo("minagri");
            assertThat(s.kind()).isEqualTo(ClientScopeKind.INSTITUTION);
        }
    }

    @Nested
    class ClientIdComposition {

        @Test
        void theAppPartIsPrefixedWithTheInstitution() {
            assertThat(ClientScopes.clientIdFor("minagri", "portal")).isEqualTo("minagri-portal");
        }

        @Test
        void itLowercases() {
            assertThat(ClientScopes.clientIdFor("minagri", "Portal")).isEqualTo("minagri-portal");
        }

        @ParameterizedTest
        @ValueSource(strings = {"minagri-portal", "minagri"})
        void aPastedClientIdIsRefused(String app) {
            assertThatThrownBy(() -> ClientScopes.clientIdFor("minagri", app))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("app part only");
        }

        @ParameterizedTest
        @ValueSource(strings = {"por tal", "portal!", "por/tal", "", "  "})
        void anUnusableAppNameIsRefused(String app) {
            assertThatThrownBy(() -> ClientScopes.clientIdFor("minagri", app))
                    .isInstanceOf(ClientScopeException.class).hasMessageContaining("not usable in a client-id");
        }

        @Test
        void aNullAppIsRefused() {
            assertThatThrownBy(() -> ClientScopes.clientIdFor("minagri", null)).isInstanceOf(ClientScopeException.class);
        }
    }

    @Nested
    class RemovedInstitutions {

        @Test
        void narrowingASelectionReportsTheLoser() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forSelected("c", List.of("minagri", "rdb")),
                    ClientScope.forSelected("c", List.of("minagri")), SLUGS)).containsExactly("rdb");
        }

        @Test
        void narrowingFromAllExpandsFirst() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forAllInstitutions("c"),
                    ClientScope.forSelected("c", List.of("minagri")), SLUGS)).containsExactly("minisante", "rdb");
        }

        @Test
        void wideningLosesNobody() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forSelected("c", List.of("minagri")),
                    ClientScope.forAllInstitutions("c"), SLUGS)).isEmpty();
        }

        @Test
        void changingOwnerReportsThePreviousOne() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forInstitution("c", "minagri"),
                    ClientScope.forInstitution("c", "rdb"), SLUGS)).containsExactly("minagri");
        }

        @Test
        void makingAnOwnedClientCrossCuttingIncludingTheOwner() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forInstitution("c", "minagri"),
                    ClientScope.forSelected("c", List.of("minagri", "rdb")), SLUGS)).isEmpty();
        }

        @Test
        void makingAClientPlatformRemovesEveryone() {
            assertThat(ClientScopes.removedInstitutions(ClientScope.forAllInstitutions("c"),
                    ClientScope.platform("c"), SLUGS)).containsExactly("minagri", "minisante", "rdb");
        }

        @Test
        void coveredIsSortedAndExpanded() {
            assertThat(ClientScope.forSelected("c", List.of("rdb", "minagri")).covered(SLUGS))
                    .containsExactly("minagri", "rdb");
            assertThat(ClientScope.platform("c").covered(SLUGS)).isEmpty();
        }
    }

    @Nested
    class Guards {

        @ParameterizedTest
        @ValueSource(strings = {ATTR_OWNER, ATTR_CROSS_CUTTING, ATTR_INSTITUTIONS})
        void aScopeAttributeInAClientUpdateIsRefused(String key) {
            assertThat(ClientScopes.scopeAttributesIn(List.of("foo", key))).containsExactly(key);
        }

        @Test
        void otherAttributesStillPassThrough() {
            assertThat(ClientScopes.scopeAttributesIn(List.of("pkce.code.challenge.method"))).isEmpty();
            assertThat(ClientScopes.scopeAttributesIn(null)).isEmpty();
        }

        @Test
        void anOwnedClientRefusesAnotherInstitutionsGroup() {
            ClientScope s = ClientScope.forInstitution("minagri-x", "minagri");
            assertThat(ClientScopes.institutionsOutsideScope(s, List.of("MINISANTE"), SLUGS))
                    .containsExactly("minisante");
            assertThat(ClientScopes.outsideScopeMessage("minagri-x", s, List.of("minisante")))
                    .contains("owned by institution 'minagri'").contains("['minisante']");
        }

        @Test
        void anOwnedClientAcceptsItsOwnInstitution() {
            ClientScope s = ClientScope.forInstitution("minagri-x", "minagri");
            assertThat(ClientScopes.institutionsOutsideScope(s, List.of("minagri", "MINAGRI"), SLUGS)).isEmpty();
        }

        @Test
        void aCrossCuttingClientAcceptsTheInstitutionsInScope() {
            ClientScope s = ClientScope.forSelected("c", List.of("rdb"));
            assertThat(ClientScopes.institutionsOutsideScope(s, List.of("rdb"), SLUGS)).isEmpty();
        }

        @Test
        void aCrossCuttingClientRefusesOneOutsideItsScope() {
            ClientScope s = ClientScope.forSelected("c", List.of("rdb"));
            assertThat(ClientScopes.institutionsOutsideScope(s, List.of("minagri", "minagri"), SLUGS))
                    .containsExactly("minagri");
            assertThat(ClientScopes.outsideScopeMessage("c", s, List.of("minagri")))
                    .contains("a cross-cutting client scoped to ['rdb']");
            assertThat(ClientScopes.outsideScopeMessage("c", ClientScope.forSelected("c", List.of()), List.of("minagri")))
                    .contains("scoped to no institution");
        }

        @Test
        void anUnscopedClientIsLeftAlone() {
            assertThat(ClientScopes.institutionsOutsideScope(ClientScope.platform("x"), List.of("minagri"), SLUGS))
                    .isEmpty();
        }

        @Test
        void aGroupThatIsNotAnInstitutionIsLeftAlone() {
            ClientScope s = ClientScope.forInstitution("minagri-x", "minagri");
            assertThat(ClientScopes.institutionsOutsideScope(s, List.of("finance-dept"), SLUGS)).isEmpty();
        }
    }

    @Nested
    class Covers {

        @Test
        void blankSlugIsNeverCovered() {
            assertThat(ClientScope.forAllInstitutions("c").covers("")).isFalse();
            assertThat(ClientScope.forAllInstitutions("c").covers(null)).isFalse();
            assertThat(ClientScope.forAllInstitutions("c").covers("  ")).isFalse();
        }

        @Test
        void coversTrimsTheSlug() {
            assertThat(ClientScope.forInstitution("c", "rdb").covers(" rdb ")).isTrue();
        }
    }
}
