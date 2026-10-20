package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;

class AccessMatrixSheetTest {

    static final SheetMeta META = new SheetMeta("minagri", List.of("minagri-portal", "irembo"), "abc",
            "2026-10-20T08:00:00.000000+00:00", "admin.minagri \u00e9");

    @Nested
    class Signing {
        @Test
        void payload_and_signature_match_the_reference_byte_for_byte() {
            assertThat(META.canonicalPayload()).isEqualTo(
                    "{\"clients\":[\"minagri-portal\",\"irembo\"],\"export_id\":\"abc\","
                            + "\"exported_at\":\"2026-10-20T08:00:00.000000+00:00\","
                            + "\"exported_by\":\"admin.minagri \\u00e9\",\"format\":\"gorsso-access-matrix\","
                            + "\"institution\":\"minagri\",\"version\":1}");
            // Computed with the Python reference (hmac.new(b"s3cret", body, sha256)).
            assertThat(AccessMatrixSheet.sign(META, "s3cret"))
                    .isEqualTo("b907e531e6ac122bdda82b60d53c2155b755519e06c33f0d25009320009f36a2");
        }

        @Test
        void verify_is_strict() {
            String sig = AccessMatrixSheet.sign(META, "k");
            assertThat(AccessMatrixSheet.verify(META, sig, "k")).isTrue();
            assertThat(AccessMatrixSheet.verify(META, sig, "other")).isFalse();
            assertThat(AccessMatrixSheet.verify(META, null, "k")).isFalse();
            assertThat(AccessMatrixSheet.verify(META, "", "k")).isFalse();
            SheetMeta relabelled = new SheetMeta("minisante", META.clients(), META.exportId(), META.exportedAt(),
                    META.exportedBy());
            assertThat(AccessMatrixSheet.verify(relabelled, sig, "k")).isFalse();
            SheetMeta widened = new SheetMeta("minagri", List.of("minagri-portal", "irembo", "x"), META.exportId(),
                    META.exportedAt(), META.exportedBy());
            assertThat(AccessMatrixSheet.verify(widened, sig, "k")).isFalse();
            assertThat(AccessMatrixSheet.sign(META, "")).hasSize(64);
        }

        @Test
        void iso_utc_format() {
            assertThat(SheetMeta.isoUtc(Instant.parse("2026-10-20T08:00:00.5Z")))
                    .isEqualTo("2026-10-20T08:00:00.500000+00:00");
        }
    }

    @Nested
    class Age {
        final Instant now = Instant.parse("2026-10-20T08:00:00Z");

        SheetMeta at(String exportedAt) {
            return new SheetMeta("m", List.of("c"), "x", exportedAt, "me");
        }

        @Test
        void thirty_days_is_the_limit() {
            assertThat(AccessMatrixSheet.isExpired(at("2026-09-20T08:00:00+00:00"), now)).isFalse();
            assertThat(AccessMatrixSheet.isExpired(at("2026-09-20T07:59:59+00:00"), now)).isTrue();
            assertThat(AccessMatrixSheet.isExpired(at("2026-09-19T08:00:00Z"), now)).isTrue();
            assertThat(AccessMatrixSheet.isExpired(at("2026-10-19T08:00:00"), now)).isFalse(); // naive = UTC
            assertThat(AccessMatrixSheet.isExpired(at("2026-10-19"), now)).isFalse();
            assertThat(AccessMatrixSheet.isExpired(at("2026-11-19T08:00:00+02:00"), now)).isFalse(); // future
        }

        @Test
        void unparseable_is_expired() {
            assertThat(AccessMatrixSheet.isExpired(at("yesterday"), now)).isTrue();
            assertThat(AccessMatrixSheet.isExpired(at(""), now)).isTrue();
        }
    }

    @Test
    void filename() {
        assertThat(AccessMatrixSheet.filenameFor("minagri", "2026-10-20T08:00:00+00:00"))
                .isEqualTo("access-matrix-minagri-2026-10-20.xlsx");
        assertThat(AccessMatrixSheet.filenameFor("../evil/", "x")).isEqualTo("access-matrix-evil-export.xlsx");
        assertThat(AccessMatrixSheet.filenameFor("//", "2026-10-20")).isEqualTo("access-matrix-institution-2026-10-20.xlsx");
    }

    @Test
    void file_sha256() {
        assertThat(AccessMatrixSheet.fileSha256("abc".getBytes()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Nested
    class Marks {
        static Stream<Object> truthy() {
            return Stream.of("YES", "yes", " Y ", "x", "TRUE", 1, 1.0, true, "\u2713", "\u2714");
        }

        static Stream<Object> falsy() {
            return Stream.of(null, "", "no", "N", 0, 0.0, false, "-", "  ");
        }

        static Stream<Object> invalid() {
            return Stream.of("maybe", "grant", "YESS", 2, 1.5);
        }

        @ParameterizedTest
        @MethodSource("truthy")
        void what_counts_as_a_mark(Object value) {
            assertThat(AccessMatrixSheet.readMark(value)).isTrue();
        }

        @ParameterizedTest
        @MethodSource("falsy")
        void what_counts_as_no_mark(Object value) {
            assertThat(AccessMatrixSheet.readMark(value)).isFalse();
        }

        @ParameterizedTest
        @MethodSource("invalid")
        void anything_else_is_reported_not_guessed(Object value) {
            assertThat(AccessMatrixSheet.readMark(value)).isNull();
        }

        @Test
        void an_employee_number_excel_turned_into_a_number_still_matches() {
            assertThat(AccessMatrixSheet.text(12345.0)).isEqualTo("12345");
            assertThat(AccessMatrixSheet.text(" E1 ")).isEqualTo("E1");
            assertThat(AccessMatrixSheet.text(null)).isEmpty();
            assertThat(AccessMatrixSheet.text(12.5)).isEqualTo("12.5");
        }

        @Test
        void python_repr() {
            assertThat(AccessMatrixSheet.pyRepr("perhaps")).isEqualTo("'perhaps'");
            assertThat(AccessMatrixSheet.pyRepr("it's")).isEqualTo("\"it's\"");
            assertThat(AccessMatrixSheet.pyRepr("a'\"b")).isEqualTo("'a\\'\"b'");
        }
    }

    @Nested
    class Classify {
        @Test
        void an_unmarked_cell_is_never_a_revoke() {
            assertThat(AccessMatrixSheet.classify(false, true, true, AccountState.EXISTING, "active", true))
                    .contains(new Verdict("removal_ignored", null));
            assertThat(AccessMatrixSheet.classify(false, false, true, AccountState.EXISTING, "active", true)).isEmpty();
        }

        @Test
        void a_mark_on_existing_access_is_a_no_op() {
            assertThat(AccessMatrixSheet.classify(true, true, false, AccountState.ELSEWHERE, "terminated", false))
                    .contains(new Verdict("already_has_access", null));
        }

        @Test
        void the_matrix_grant_rule_decides() {
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.EXISTING, "active", true))
                    .contains(new Verdict("will_grant", null));
            assertThat(AccessMatrixSheet.classify(true, false, false, AccountState.EXISTING, "active", true))
                    .contains(new Verdict("rejected", "not_manageable"));
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.AMBIGUOUS, "active", true))
                    .contains(new Verdict("rejected", "account_unresolved"));
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.EXISTING, "terminated", true))
                    .contains(new Verdict("rejected", "employee_left"));
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.NONE, "active", true))
                    .contains(new Verdict("will_create_account", null));
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.NONE, "active", false))
                    .contains(new Verdict("rejected", "cannot_create_account"));
            assertThat(AccessMatrixSheet.classify(true, false, true, AccountState.NONE, "terminated", true))
                    .contains(new Verdict("rejected", "employee_left"));
        }

        @Test
        void messages() {
            assertThat(AccessMatrixSheet.outcomeMessage("will_grant", null)).isEqualTo("Will be given access.");
            assertThat(AccessMatrixSheet.outcomeMessage("rejected", "not_in_roster")).startsWith("This employee number");
            assertThat(AccessMatrixSheet.outcomeMessage("rejected", "employee_left")).contains("leaver is not given access");
            assertThat(AccessMatrixSheet.outcomeMessage("rejected", "join_value_missing")).isEqualTo("join_value_missing");
            assertThat(AccessMatrixSheet.outcomeMessage("weird", null)).isNull();
        }
    }
}
