package rw.gov.risa.sso.wrapper.policy.joinkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of tests/e2e/test_join_key.py (pure half) plus extra edge cases. */
class JoinKeyTest {

    static final String NID = "1199780012345678";
    static final String OTHER_NID = "1199780087654321";

    static JoinKeyUser user(String id, String username, String email, String... attrKv) {
        Map<String, List<String>> attrs = null;
        if (attrKv.length > 0) {
            attrs = new HashMap<>();
            for (int i = 0; i < attrKv.length; i += 2) {
                attrs.put(attrKv[i], List.of(attrKv[i + 1]));
            }
        }
        return new JoinKeyUser(id, username, email, attrs);
    }

    static JoinColumnSetting explicit(String clientId, JoinColumn column) {
        return new JoinColumnSetting(clientId, column, true);
    }

    @Nested
    class Setting {

        @Test
        void aClientThatRecordedNothingDefaultsToEmailButSaysSo() {
            JoinColumnSetting s = JoinColumnSetting.parse("minagri-kubaka", Map.of());
            assertThat(s.column()).isEqualTo(JoinColumn.EMAIL);
            assertThat(s.explicit()).isFalse();
            assertThat(s.claim()).isEqualTo("join_key");
            assertThat(JoinColumnSetting.parse("c", null).explicit()).isFalse();
        }

        @Test
        void aRecordedColumnCarriesItsProvenance() {
            JoinColumnSetting s = JoinColumnSetting.parse("c", Map.of(
                    JoinKeys.ATTR_JOIN_COLUMN, "nid",
                    JoinKeys.ATTR_CHANGED_BY, "minagri.admin",
                    JoinKeys.ATTR_CHANGED_AT, "2026-09-03T10:00:00+00:00"));
            assertThat(s.column()).isEqualTo(JoinColumn.NID);
            assertThat(s.explicit()).isTrue();
            assertThat(s.changedBy()).isEqualTo("minagri.admin");
            assertThat(s.changedAt()).isEqualTo("2026-09-03T10:00:00+00:00");
        }

        @Test
        void storedCaseIsIgnoredOnRead() {
            assertThat(JoinColumnSetting.parse("c", Map.of(JoinKeys.ATTR_JOIN_COLUMN, " PHONE ")).column())
                    .isEqualTo(JoinColumn.PHONE);
        }

        @Test
        void aHandEditedAttributeFallsBackAndIsReportedNotErased() {
            JoinColumnSetting s = JoinColumnSetting.parse("c", Map.of(JoinKeys.ATTR_JOIN_COLUMN, "employee_number"));
            assertThat(s.column()).isEqualTo(JoinColumn.EMAIL);
            assertThat(s.explicit()).isFalse();
            assertThat(s.malformed()).isEqualTo("employee_number");
            assertThat(s.describe()).containsEntry("malformed_value", "employee_number");
        }

        @Test
        void blankProvenanceReadsAsNull() {
            JoinColumnSetting s = JoinColumnSetting.parse("c", Map.of(JoinKeys.ATTR_JOIN_COLUMN, "nid",
                    JoinKeys.ATTR_CHANGED_BY, "  "));
            assertThat(s.changedBy()).isNull();
            assertThat(s.changedAt()).isNull();
        }

        @Test
        void savingWritesEveryKeyItOwnsSoProvenanceCannotGoStale() {
            Map<String, String> written = new JoinColumnSetting("c", JoinColumn.PHONE, false)
                    .toAttributes("admin", Instant.parse("2026-09-03T10:00:00Z"));
            assertThat(written).containsEntry(JoinKeys.ATTR_JOIN_COLUMN, "phone")
                    .containsEntry(JoinKeys.ATTR_CHANGED_BY, "admin")
                    .containsEntry(JoinKeys.ATTR_CHANGED_AT, "2026-09-03T10:00:00+00:00");
            assertThat(written.keySet()).containsExactlyInAnyOrderElementsOf(JoinKeys.JOIN_COLUMN_ATTRIBUTES);
            assertThat(new JoinColumnSetting("c", JoinColumn.NID, true).toAttributes(null, Instant.EPOCH))
                    .containsEntry(JoinKeys.ATTR_CHANGED_BY, "");
        }

        @Test
        void changedAtIncludesMicrosecondsLikePythonIsoformat() {
            assertThat(JoinColumnSetting.isoformat(Instant.parse("2026-09-03T10:00:00.123456789Z")))
                    .isEqualTo("2026-09-03T10:00:00.123456+00:00");
        }

        @Test
        void theRoundTripIsExplicit() {
            Map<String, String> a = new JoinColumnSetting("c", JoinColumn.NID, true).toAttributes("me", Instant.EPOCH);
            JoinColumnSetting back = JoinColumnSetting.parse("c", a);
            assertThat(back.column()).isEqualTo(JoinColumn.NID);
            assertThat(back.explicit()).isTrue();
            assertThat(back.changedBy()).isEqualTo("me");
        }

        @Test
        void withColumnDropsProvenanceAndIsExplicit() {
            JoinColumnSetting s = JoinColumnSetting.parse("c", Map.of(JoinKeys.ATTR_CHANGED_BY, "x"));
            JoinColumnSetting after = s.withColumn(JoinColumn.NID);
            assertThat(after.explicit()).isTrue();
            assertThat(after.changedBy()).isNull();
            assertThat(after.clientId()).isEqualTo("c");
        }

        @Test
        void describeHasTheWireKeys() {
            assertThat(explicit("c", JoinColumn.NID).describe()).containsOnlyKeys("client_id", "join_column", "claim",
                    "user_source", "explicit", "changed_by", "changed_at", "malformed_value")
                    .containsEntry("user_source", "national_id");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "employee_number"})
        void theWritePathRefusesAnythingItCannotStore(String bad) {
            assertThatThrownBy(() -> JoinKeys.requested(bad)).isInstanceOf(JoinColumnException.class);
        }

        @Test
        void theWritePathRefusesNull() {
            assertThatThrownBy(() -> JoinKeys.requested(null)).isInstanceOf(JoinColumnException.class)
                    .hasMessageContaining("join_column is required");
        }

        @Test
        void caseAndSpaceAreInputNoise() {
            assertThat(JoinKeys.requested("NID ")).isEqualTo(JoinColumn.NID);
            assertThat(JoinKeys.requested(" Email")).isEqualTo(JoinColumn.EMAIL);
        }

        @Test
        void choicesListEveryColumnWithEmailAsDefault() {
            List<Map<String, Object>> choices = JoinKeys.choices();
            assertThat(choices).extracting(m -> m.get("value")).containsExactly("nid", "phone", "email");
            assertThat(choices).filteredOn(m -> Boolean.TRUE.equals(m.get("default")))
                    .extracting(m -> m.get("value")).containsExactly("email");
        }

        @Test
        void joinColumnAttributesAreRefusedInAGenericUpdate() {
            assertThat(JoinKeys.joinColumnAttributesIn(List.of("x", JoinKeys.ATTR_CHANGED_AT, JoinKeys.ATTR_JOIN_COLUMN)))
                    .containsExactly(JoinKeys.ATTR_JOIN_COLUMN, JoinKeys.ATTR_CHANGED_AT);
            assertThat(JoinKeys.joinColumnAttributesIn(null)).isEmpty();
        }
    }

    @Nested
    class Values {

        @Test
        void aValueIsReadFromTheShapeKeycloakActuallyStores() {
            ColumnSpec nid = JoinKeys.specFor(JoinColumn.NID);
            ColumnSpec email = JoinKeys.specFor(JoinColumn.EMAIL);
            assertThat(nid.read(user("u1", "jean", null, "national_id", NID))).isEqualTo(NID);
            assertThat(email.read(user("u1", "jean", "jean@gov.rw"))).isEqualTo("jean@gov.rw");
            assertThat(nid.read(new JoinKeyUser("u1", null, null, null))).isEmpty();
            assertThat(nid.read(new JoinKeyUser("u1", null, null, Map.of("national_id", List.of())))).isEmpty();
            assertThat(nid.read(null)).isEmpty();
        }

        @Test
        void normalisationIsForComparisonOnly() {
            ColumnSpec nid = JoinKeys.specFor(JoinColumn.NID);
            ColumnSpec email = JoinKeys.specFor(JoinColumn.EMAIL);
            assertThat(nid.normalise("1199 7800 1234 5678")).isEqualTo(NID);
            assertThat(email.normalise("Jean@Gov.RW")).isEqualTo("jean@gov.rw");
            assertThat(nid.read(user("u1", "j", null, "national_id", "1199 7800 1234 5678"))).isNotEqualTo(NID);
            assertThat(nid.normalise(null)).isEmpty();
            assertThat(JoinKeys.specFor(JoinColumn.PHONE).normalise("(078) 812-34.56/7")).isEqualTo("07881234567");
        }

        static Stream<Arguments> shapes() {
            return Stream.of(
                    Arguments.of(JoinColumn.NID, NID, true),
                    Arguments.of(JoinColumn.NID, "1199 7800 1234 5678", true),
                    Arguments.of(JoinColumn.NID, "119978001234567", false),
                    Arguments.of(JoinColumn.NID, "11997800123456789", false),
                    Arguments.of(JoinColumn.NID, "11997800123456A8", false),
                    Arguments.of(JoinColumn.PHONE, "+250788123456", true),
                    Arguments.of(JoinColumn.PHONE, "0788123456", true),
                    Arguments.of(JoinColumn.PHONE, "078-812-3456", true),
                    Arguments.of(JoinColumn.PHONE, "12345", false),
                    Arguments.of(JoinColumn.PHONE, "1234567890123456", false),
                    Arguments.of(JoinColumn.PHONE, "++250788123456", false),
                    Arguments.of(JoinColumn.EMAIL, "jean@gov.rw", true),
                    Arguments.of(JoinColumn.EMAIL, "jean.at.gov.rw", false),
                    Arguments.of(JoinColumn.EMAIL, "jean@gov", false),
                    Arguments.of(JoinColumn.EMAIL, "a@b@c.rw", false),
                    Arguments.of(JoinColumn.EMAIL, "", true),
                    Arguments.of(JoinColumn.NID, "   ", true));
        }

        @ParameterizedTest
        @MethodSource("shapes")
        void shapeValidation(JoinColumn column, String value, boolean valid) {
            assertThat(JoinKeys.specFor(column).validate(value).isEmpty()).isEqualTo(valid);
        }

        @Test
        void theProblemNamesTheShape() {
            assertThat(JoinKeys.specFor(JoinColumn.NID).validate("12345"))
                    .hasValue("National ID '12345' is not a usable nid value: expected exactly 16 digits.");
        }
    }

    @Nested
    class Refusals {

        @Test
        void aUserWithNoValueIsRefusedAndToldWhereTheFixIs() {
            JoinKeyResolution r = JoinKeyResolver.resolve(explicit("minagri-kubaka", JoinColumn.NID),
                    user("u1", "jean", "jean@gov.rw"), null);
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).isEqualTo(JoinKeyError.MISSING);
            assertThat(r.message()).contains("national_id").contains("minagri-kubaka");
            assertThat(r.describe()).doesNotContainKey("value").containsEntry("error", "join_key_missing");
        }

        @Test
        void aMalformedValueIsADifferentRefusalFromAMissingOne() {
            JoinKeyResolution r = JoinKeyResolver.resolve(explicit("minagri-kubaka", JoinColumn.NID),
                    user("u1", "jean", null, "national_id", "12345"), null);
            assertThat(r.error()).isEqualTo(JoinKeyError.INVALID);
            assertThat(r.message()).contains("16 digits");
            assertThat(r.describe().get("value")).isNull();
        }

        @Test
        void formatValidationCanBeTurnedOffWithoutDroppingPresence() {
            JoinColumnSetting s = explicit("c", JoinColumn.NID);
            assertThat(JoinKeyResolver.resolve(s, user("u1", "j", null, "national_id", "12345"), null, false).ok())
                    .isTrue();
            assertThat(JoinKeyResolver.resolve(s, user("u1", "j", null), null, false).ok()).isFalse();
        }

        @Test
        void twoAccountsSharingTheKeyIsRefusedNotResolvedToOne() {
            JoinKeyUser signingIn = user("u1", "jean", null, "national_id", NID);
            JoinKeyResolution r = JoinKeyResolver.resolve(explicit("minagri-kubaka", JoinColumn.NID), signingIn,
                    List.of(signingIn, user("u2", "claudine", null, "national_id", NID)));
            assertThat(r.error()).isEqualTo(JoinKeyError.NOT_UNIQUE);
            assertThat(r.conflicts()).containsExactly("u2");
            assertThat(r.message()).doesNotContain("u2").startsWith("2 accounts share");
            assertThat(r.describe()).containsEntry("conflicts", 1);
        }

        @Test
        void duplicatesAreComparedNormalised() {
            JoinKeyUser signingIn = user("u1", "jean", "Jean@Gov.rw");
            JoinKeyResolution r = JoinKeyResolver.resolve(explicit("c", JoinColumn.EMAIL), signingIn,
                    List.of(user("u2", "x", "jean@gov.RW")));
            assertThat(r.error()).isEqualTo(JoinKeyError.NOT_UNIQUE);
        }

        @Test
        void theUserThemselvesIsNeverTheirOwnDuplicate() {
            JoinKeyUser signingIn = user("u1", "jean", null, "national_id", NID);
            assertThat(JoinKeyResolver.resolve(explicit("c", JoinColumn.NID), signingIn, List.of(signingIn)).ok())
                    .isTrue();
        }

        @Test
        void aSearchHitWhoseValueDiffersIsNotADuplicate() {
            JoinKeyUser signingIn = user("u1", "jean", null, "national_id", NID);
            assertThat(JoinKeyResolver.resolve(explicit("c", JoinColumn.NID), signingIn,
                    List.of(signingIn, user("u2", "claudine", null, "national_id", OTHER_NID))).ok()).isTrue();
        }

        @Test
        void aHolderWithNoIdIsIgnored() {
            JoinKeyUser signingIn = user("u1", "jean", null, "national_id", NID);
            assertThat(JoinKeyResolver.resolve(explicit("c", JoinColumn.NID), signingIn,
                    List.of(user(null, "ghost", null, "national_id", NID))).ok()).isTrue();
        }

        @Test
        void aResolvedKeyReportsTheValueTheTokenWillCarry() {
            JoinKeyResolution r = JoinKeyResolver.resolve(explicit("c", JoinColumn.EMAIL),
                    user("u1", "jean", "jean@gov.rw"), List.of());
            assertThat(r.ok()).isTrue();
            assertThat(r.value()).isEqualTo("jean@gov.rw");
            assertThat(r.describe()).containsEntry("claim", "join_key").containsEntry("value", "jean@gov.rw")
                    .doesNotContainKey("error");
        }

        @Test
        void anUnnamedClientIsCalledThisApplication() {
            JoinKeyResolution r = JoinKeyResolver.resolve(new JoinColumnSetting("", JoinColumn.NID, true),
                    user("u1", "j", null), null);
            assertThat(r.message()).contains("this application");
        }
    }

    @Nested
    class TokenSide {

        @Test
        void emailGetsAPropertyMapperAndAnAttributeGetsAnAttributeMapper() {
            MapperSpec email = JoinKeys.specFor(JoinColumn.EMAIL).mapper();
            MapperSpec nid = JoinKeys.specFor(JoinColumn.NID).mapper();
            assertThat(email.protocolMapper()).isEqualTo("oidc-usermodel-property-mapper");
            assertThat(nid.protocolMapper()).isEqualTo("oidc-usermodel-attribute-mapper");
            assertThat(nid.config()).containsEntry("user.attribute", "national_id");
            assertThat(email.config().get("claim.name")).isEqualTo(nid.config().get("claim.name")).isEqualTo("join_key");
            assertThat(email.name()).isEqualTo(nid.name()).isEqualTo("sso-join-key");
            for (MapperSpec m : List.of(email, nid)) {
                assertThat(m.config()).containsEntry("access.token.claim", "true")
                        .containsEntry("id.token.claim", "true").containsEntry("userinfo.token.claim", "true")
                        .containsEntry("introspection.token.claim", "true").containsEntry("multivalued", "false");
                assertThat(m.protocol()).isEqualTo("openid-connect");
            }
            assertThat(JoinKeys.specFor(JoinColumn.PHONE).searchField()).isEqualTo("phone");
        }

        @Test
        void aMissingMapperIsCreated() {
            MapperReconciler.Action a = MapperReconciler.plan(explicit("c", JoinColumn.NID),
                    List.of(new MapperSpec("m0", "other", "openid-connect", "x", Map.of())));
            assertThat(a.kind()).isEqualTo(MapperReconciler.Kind.CREATED);
            assertThat(a.write().id()).isNull();
            assertThat(a.write().config()).containsEntry("user.attribute", "national_id");
            assertThat(MapperReconciler.plan(explicit("c", JoinColumn.NID), null).kind())
                    .isEqualTo(MapperReconciler.Kind.CREATED);
        }

        @Test
        void aMatchingMapperIsUnchanged() {
            MapperSpec existing = new MapperSpec("m1", "sso-join-key", "openid-connect",
                    "oidc-usermodel-attribute-mapper", Map.of("user.attribute", "phone", "claim.name", "join_key"));
            MapperReconciler.Action a = MapperReconciler.plan(explicit("c", JoinColumn.PHONE), List.of(existing));
            assertThat(a.kind()).isEqualTo(MapperReconciler.Kind.UNCHANGED);
            assertThat(a.kind().code()).isEqualTo("unchanged");
        }

        @Test
        void aDifferentColumnUpdatesTheSameMapperMergingConfig() {
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("user.attribute", "email");
            cfg.put("claim.name", "join_key");
            cfg.put("custom.server.default", "keep-me");
            MapperSpec existing = new MapperSpec("m1", "sso-join-key", "openid-connect",
                    "oidc-usermodel-property-mapper", cfg);
            MapperReconciler.Action a = MapperReconciler.plan(explicit("c", JoinColumn.NID), List.of(existing));
            assertThat(a.kind()).isEqualTo(MapperReconciler.Kind.UPDATED);
            assertThat(a.write().id()).isEqualTo("m1");
            assertThat(a.write().protocolMapper()).isEqualTo("oidc-usermodel-attribute-mapper");
            assertThat(a.write().config()).containsEntry("user.attribute", "national_id")
                    .containsEntry("custom.server.default", "keep-me");
        }

        @Test
        void theFailureMessageNamesTheClaim() {
            assertThat(MapperReconciler.WRITE_FAILED_MESSAGE).contains("'join_key'").contains("manage-clients");
        }
    }

    @Nested
    class Readiness {

        @Test
        void readinessNamesEveryWayAColumnWouldBlockSomeone() {
            List<ReadinessRow> rows = JoinKeyResolver.readiness(explicit("minagri-kubaka", JoinColumn.NID), List.of(
                    user("ok", "fine", null, "national_id", NID),
                    user("none", "missing", null),
                    user("bad", "malformed", null, "national_id", "12345"),
                    user("dup1", "twin-a", null, "national_id", OTHER_NID),
                    user("dup2", "twin-b", null, "national_id", OTHER_NID)));
            Map<String, JoinKeyError> byUser = new HashMap<>();
            rows.forEach(r -> byUser.put(r.userId(), r.error()));
            assertThat(byUser).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "none", JoinKeyError.MISSING,
                    "bad", JoinKeyError.INVALID,
                    "dup1", JoinKeyError.NOT_UNIQUE,
                    "dup2", JoinKeyError.NOT_UNIQUE));
            ReadinessRow dup = rows.stream().filter(r -> r.userId().equals("dup1")).findFirst().orElseThrow();
            assertThat(dup.detail()).isEqualTo("2 users assigned to this client share this national id");
            assertThat(dup.describe()).containsEntry("error", "join_key_not_unique");
            assertThat(rows.get(0).detail()).isEqualTo("no national_id on record");
            assertThat(rows.get(1).detail()).isEqualTo("expected exactly 16 digits");
        }

        @Test
        void threeWayDuplicatesAreAllReported() {
            List<ReadinessRow> rows = JoinKeyResolver.readiness(explicit("c", JoinColumn.EMAIL), List.of(
                    user("a", "a", "x@gov.rw"), user("b", "b", "X@gov.rw"), user("c", "c", "x@GOV.rw")));
            assertThat(rows).hasSize(3).allMatch(r -> r.detail().startsWith("3 users"));
        }

        @Test
        void theSameUserListedTwiceIsNotADuplicate() {
            JoinKeyUser u = user("a", "a", "x@gov.rw");
            assertThat(JoinKeyResolver.readiness(explicit("c", JoinColumn.EMAIL), List.of(u, u))).isEmpty();
        }

        @Test
        void readinessAnswersUniquenessWithinTheAssignedPopulation() {
            assertThat(JoinKeyResolver.readiness(explicit("c", JoinColumn.PHONE),
                    List.of(user("u1", "j", null, "phone", "+250788123456")))).isEmpty();
        }

        @Test
        void formatCheckCanBeTurnedOffInReadiness() {
            assertThat(JoinKeyResolver.readiness(explicit("c", JoinColumn.NID),
                    List.of(user("u1", "j", null, "national_id", "1")), false)).isEmpty();
        }

        @Test
        void blankUsernameIsNull() {
            List<ReadinessRow> rows = JoinKeyResolver.readiness(explicit("c", JoinColumn.NID),
                    List.of(user("u1", "  ", null)));
            assertThat(rows.get(0).username()).isNull();
        }
    }
}
