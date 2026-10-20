package rw.gov.risa.sso.wrapper.policy.joinkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The confirm-required rule (port of test_join_key_backfill.py::TestRekeyWarning), the identifier
 * write gate, the login-gate applicability and the backfill worklist.
 */
class JoinKeyWriteRulesTest {

    static final JoinColumnSetting UNSET = JoinColumnSetting.unset("minagri-portal");
    static final JoinColumnSetting EMAIL = new JoinColumnSetting("minagri-portal", JoinColumn.EMAIL, true);
    static final JoinColumnSetting NID = new JoinColumnSetting("minagri-portal", JoinColumn.NID, true);

    @Nested
    class RekeyWarning {

        @Test
        void aFirstTimeSetOnAClientWithUsersNeedsNoConfirmation() {
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(UNSET, JoinColumn.NID, 0, 500, false, false);
            assertThat(d.confirmationRequired()).isFalse();
            assertThat(d.rekeying()).isFalse();
            assertThat(d.assignedUsers()).isZero();
            assertThat(d.after().column()).isEqualTo(JoinColumn.NID);
            assertThat(d.after().explicit()).isTrue();
        }

        @Test
        void rekeyingAConfiguredClientWithUsersIsRefusedUnconfirmed() {
            assertThat(JoinColumnChangeGate.isRekeying(EMAIL, JoinColumn.NID)).isTrue();
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 0, 42, false, false);
            assertThat(d.confirmationRequired()).isTrue();
            assertThat(d.conflictDetails()).containsEntry("assigned_users", 42);
            assertThat(d.message()).contains("42 user(s) currently assigned");
        }

        @Test
        void theMessageNamesBothColumnsSoTheEffectIsReadable() {
            String m = JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 0, 3, false, false).message().toLowerCase();
            assertThat(m).contains("email address today").contains("national id afterwards")
                    .contains("from 'email' to 'nid'");
        }

        @Test
        void confirmingAppliesIt() {
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 0, 42, false, true);
            assertThat(d.confirmationRequired()).isFalse();
            assertThat(d.assignedUsers()).isEqualTo(42);
            assertThat(d.message()).isNull();
        }

        @Test
        void sessionsAndUsersAreReportedTogether() {
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 7, 42, false, false);
            assertThat(d.message()).contains("7 active session(s)").contains("42 user(s) currently assigned")
                    .contains(" and ");
            assertThat(d.conflictDetails()).containsEntry("active_sessions", 7)
                    .containsEntry("requested", Map.of("join_column", "nid"));
        }

        @Test
        void aLiveSessionStillGuardsAFirstTimeSet() {
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(UNSET, JoinColumn.NID, 2, 0, false, false);
            assertThat(d.confirmationRequired()).isTrue();
            assertThat(d.conflictDetails()).containsEntry("active_sessions", 2);
        }

        @Test
        void explicitlyChoosingTheDefaultIsStillAChangeForSessions() {
            assertThat(JoinColumnChangeGate.isChanging(UNSET, JoinColumn.EMAIL)).isTrue();
            assertThat(JoinColumnChangeGate.evaluate(UNSET, JoinColumn.EMAIL, 1, 0, false, false)
                    .confirmationRequired()).isTrue();
        }

        @Test
        void settingTheSameColumnAgainIsNotARekey() {
            assertThat(JoinColumnChangeGate.isChanging(NID, JoinColumn.NID)).isFalse();
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(NID, JoinColumn.NID, 9, 500, false, false);
            assertThat(d.confirmationRequired()).isFalse();
            assertThat(d.assignedUsers()).isZero();
        }

        @Test
        void anUnreadablePopulationDoesNotBlockTheChange() {
            assertThat(JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 0, 0, false, false).confirmationRequired())
                    .isFalse();
        }

        @Test
        void aTruncatedCountIsReportedAsAFloor() {
            JoinColumnChangeGate.Decision d = JoinColumnChangeGate.evaluate(EMAIL, JoinColumn.NID, 0, 1000, true, false);
            assertThat(d.message()).contains("at least 1000 user(s)");
            assertThat(d.conflictDetails()).containsEntry("assigned_users_is_floor", true);
        }

        @Test
        void samlClientsCannotCarryAJoinKey() {
            assertThat(JoinColumnChangeGate.protocolRefusal("c", "saml")).hasValueSatisfying(
                    m -> assertThat(m).contains("'saml'").contains("cannot be configured on a SAML client"));
            assertThat(JoinColumnChangeGate.protocolRefusal("c", null)).isEmpty();
            assertThat(JoinColumnChangeGate.protocolRefusal("c", "openid-connect")).isEmpty();
        }
    }

    @Nested
    class IdentifierWrites {

        @Test
        void candidatesAreOnlyTheKeysTheWriteCarries() {
            assertThat(IdentifierWriteCheck.candidates(false, null, null)).isEmpty();
            assertThat(IdentifierWriteCheck.candidates(true, " a@b.rw ", Map.of("national_id", List.of("1"),
                    "phone", List.of()))).containsExactly(
                    new IdentifierWriteCheck.Candidate(JoinColumn.NID, "1"),
                    new IdentifierWriteCheck.Candidate(JoinColumn.EMAIL, "a@b.rw"));
            assertThat(IdentifierWriteCheck.candidates(true, "   ", Map.of())).isEmpty();
        }

        @Test
        void aMalformedValueIs400() {
            var c = new IdentifierWriteCheck.Candidate(JoinColumn.NID, "12345");
            assertThat(IdentifierWriteCheck.formatRefusal(c)).hasValueSatisfying(r -> {
                assertThat(r.status()).isEqualTo(400);
                assertThat(r.message()).contains("16 digits").contains("'national_id'");
            });
            assertThat(IdentifierWriteCheck.formatRefusal(new IdentifierWriteCheck.Candidate(JoinColumn.PHONE,
                    "+250788123456"))).isEmpty();
        }

        @Test
        void aValueHeldByAnotherAccountIs409WithoutNamingIt() {
            var c = new IdentifierWriteCheck.Candidate(JoinColumn.EMAIL, "Jean@gov.rw");
            var refusal = IdentifierWriteCheck.duplicateRefusal(c,
                    List.of(JoinKeyUser.of("other-id", "other-user", "jean@gov.rw")), "me");
            assertThat(refusal).hasValueSatisfying(r -> {
                assertThat(r.status()).isEqualTo(409);
                assertThat(r.message()).doesNotContain("other-id").doesNotContain("other-user");
            });
        }

        @Test
        void reSavingYourOwnValueIsNotADuplicate() {
            var c = new IdentifierWriteCheck.Candidate(JoinColumn.EMAIL, "jean@gov.rw");
            assertThat(IdentifierWriteCheck.duplicateRefusal(c, List.of(JoinKeyUser.of("me", "j", "jean@gov.rw")), "me"))
                    .isEmpty();
            assertThat(IdentifierWriteCheck.duplicateRefusal(c, null, "me")).isEmpty();
            assertThat(IdentifierWriteCheck.duplicateRefusal(c, List.of(JoinKeyUser.of("x", "x", "other@gov.rw")),
                    null)).isEmpty();
        }
    }

    @Nested
    class LoginGate {

        @Test
        void serviceAccountsDisabledEnforcementAndExemptClientsAreSkipped() {
            assertThat(JoinKeyGate.appliesTo(null, "c", true, Set.of())).isFalse();
            assertThat(JoinKeyGate.appliesTo("", "c", true, Set.of())).isFalse();
            assertThat(JoinKeyGate.appliesTo("jean", "c", false, Set.of())).isFalse();
            assertThat(JoinKeyGate.appliesTo("jean", "sso-admin-ui", true, Set.of("sso-admin-ui"))).isFalse();
            assertThat(JoinKeyGate.appliesTo("jean", "c", true, null)).isTrue();
        }

        @Test
        void unsetClientsAreSkippedUnlessConfigured() {
            assertThat(JoinKeyGate.enforcedFor(UNSET, false)).isFalse();
            assertThat(JoinKeyGate.enforcedFor(UNSET, true)).isTrue();
            assertThat(JoinKeyGate.enforcedFor(NID, false)).isTrue();
        }

        @Test
        void theRefusalBodyCarriesTheCodeAndTheColumn() {
            JoinKeyResolution r = JoinKeyResolver.resolve(NID, JoinKeyUser.of("u", "j", null), null);
            assertThat(JoinKeyGate.refusalBody(r, NID)).containsEntry("error", "join_key_missing")
                    .containsEntry("join_column", "nid").containsEntry("claim", "join_key")
                    .containsEntry("user_attribute", "national_id").containsKey("error_description");
        }
    }

    @Nested
    class Backlog {

        @Test
        void theCountsSayHowMuchIsOutstanding() {
            List<JoinColumnBacklog.App> apps = List.of(
                    new JoinColumnBacklog.App("minagri-kubaka-web", "k", true, false, true, NID),
                    new JoinColumnBacklog.App("minagri-reports", "r", true, false, true, EMAIL),
                    new JoinColumnBacklog.App("minagri-portal", "p", true, false, true, UNSET),
                    new JoinColumnBacklog.App("minagri-legacy", "l", true, false, true,
                            JoinColumnSetting.parse("minagri-legacy", Map.of())),
                    new JoinColumnBacklog.App("minagri-payroll", "pay", true, false, true,
                            JoinColumnSetting.parse("minagri-payroll", Map.of(JoinKeys.ATTR_JOIN_COLUMN, "nationalid"))),
                    new JoinColumnBacklog.App("national-payroll", "n", false, true, true,
                            JoinColumnSetting.unset("national-payroll")));
            Map<String, Object> b = JoinColumnBacklog.summarise("minagri", apps);
            assertThat(b).containsEntry("institution", "minagri").containsEntry("total", 6)
                    .containsEntry("configured", 2).containsEntry("unconfigured", 4)
                    .containsEntry("outstanding", 3).containsEntry("needs_platform_admin", 1)
                    .containsEntry("malformed", 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) b.get("items");
            Map<String, Object> shared = items.get(5);
            assertThat(shared).containsEntry("cross_cutting", true).containsEntry("configurable", false)
                    .containsEntry("owned", false);
            Map<String, Object> unset = items.get(2);
            assertThat(unset).containsEntry("configured", false);
            @SuppressWarnings("unchecked")
            Map<String, Object> jc = (Map<String, Object>) unset.get("join_column");
            assertThat(jc).containsEntry("explicit", false).containsEntry("join_column", "email");
            assertThat(b.get("choices")).isEqualTo(JoinKeys.choices());
        }

        @Test
        void aReadOnlyTierGetsTheListWithNothingConfigurable() {
            Map<String, Object> b = JoinColumnBacklog.summarise("minagri", List.of(
                    new JoinColumnBacklog.App("minagri-portal", "p", true, false, false, UNSET)));
            assertThat(b).containsEntry("outstanding", 0).containsEntry("needs_platform_admin", 1);
        }
    }
}
