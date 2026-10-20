package rw.gov.risa.sso.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HashChainTest {

    private static final String G = HashChain.GENESIS_HASH;

    @Test
    void v1MatchesPythonReference() {
        assertThat(HashChain.compute(GoldenVectors.v2Input(), G, 1)).isEqualTo(GoldenVectors.V1);
    }

    @Test
    void v2MatchesPythonReference() {
        assertThat(HashChain.compute(GoldenVectors.v2Input(), G, 2)).isEqualTo(GoldenVectors.V2);
    }

    @Test
    void v2WithWholeSecondAndNullsMatchesPython() {
        var in = new HashChain.HashInput("admin.user.created", null, GoldenVectors.T_WHOLE, Map.of(), null, null, null,
                null, null, "WARNING");
        assertThat(HashChain.compute(in, "ab".repeat(32), 2)).isEqualTo(GoldenVectors.V2_WHOLE_SECOND_EMPTY);
    }

    @Test
    void versionZeroOrLessUsesV1LikeTheReference() {
        assertThat(HashChain.compute(GoldenVectors.v2Input(), G, 0)).isEqualTo(GoldenVectors.V1);
    }

    @Test
    void pyIsoFormatOmitsZeroMicrosAndAlwaysUsesUtcOffset() {
        assertThat(HashChain.pyIsoFormat(GoldenVectors.T_MICROS)).isEqualTo("2026-10-20T09:15:30.123456+00:00");
        assertThat(HashChain.pyIsoFormat(GoldenVectors.T_WHOLE)).isEqualTo("2026-10-20T09:15:30+00:00");
        OffsetDateTime kigali = OffsetDateTime.of(2026, 10, 20, 11, 15, 30, 1_000, ZoneOffset.ofHours(2));
        assertThat(HashChain.pyIsoFormat(kigali)).isEqualTo("2026-10-20T09:15:30.000001+00:00");
    }

    @Test
    void toMicrosTruncatesNanosLikePostgres() {
        OffsetDateTime t = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 123_456_789, ZoneOffset.UTC);
        assertThat(HashChain.toMicros(t).getNano()).isEqualTo(123_456_000);
    }

    @Test
    void canonicalFormIsPipeJoined() {
        var in = new HashChain.HashInput("e", "u", GoldenVectors.T_WHOLE, Map.of("k", 1), "p", "i", "t", "a", "ip",
                "INFO");
        assertThat(HashChain.canonical(in, "prev", 2))
                .isEqualTo("prev|e|u|2026-10-20T09:15:30+00:00|{\"k\": 1}|p|i|t|a|ip|INFO");
        assertThat(HashChain.canonical(in, "prev", 1)).isEqualTo("prev|e|u|2026-10-20T09:15:30+00:00|{\"k\": 1}");
    }

    @Test
    void v2CoversActorInstitutionTargetAppIpSeverity() {
        var base = GoldenVectors.v2Input();
        String h = HashChain.compute(base, G, 2);
        var forged = new HashChain.HashInput[] {
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(), "attacker",
                    base.institution(), base.target(), base.appId(), base.ipAddress(), base.severity()),
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                    base.performedBy(), "minisante", base.target(), base.appId(), base.ipAddress(), base.severity()),
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                    base.performedBy(), base.institution(), "carol", base.appId(), base.ipAddress(), base.severity()),
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                    base.performedBy(), base.institution(), base.target(), "other-app", base.ipAddress(),
                    base.severity()),
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                    base.performedBy(), base.institution(), base.target(), base.appId(), "10.6.6.6", base.severity()),
            new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                    base.performedBy(), base.institution(), base.target(), base.appId(), base.ipAddress(), "CRITICAL"),
        };
        for (var f : forged) {
            assertThat(HashChain.compute(f, G, 2)).isNotEqualTo(h);
        }
    }

    @Test
    void v1DoesNotCoverTheActorWhichIsWhyV2Exists() {
        var base = GoldenVectors.v2Input();
        var forged = new HashChain.HashInput(base.eventType(), base.userId(), base.createdAt(), base.details(),
                "attacker", base.institution(), base.target(), base.appId(), base.ipAddress(), base.severity());
        assertThat(HashChain.compute(forged, G, 1)).isEqualTo(HashChain.compute(base, G, 1));
    }

    @Test
    void delimiterPreventsBoundaryCollisions() {
        var a = new HashChain.HashInput("e", null, GoldenVectors.T_WHOLE, Map.of(), "ab", null, "c", null, null, "INFO");
        var b = new HashChain.HashInput("e", null, GoldenVectors.T_WHOLE, Map.of(), "a", null, "bc", null, null, "INFO");
        assertThat(HashChain.compute(a, G, 2)).isNotEqualTo(HashChain.compute(b, G, 2));
    }

    @Test
    void prevHashIsPartOfTheDigest() {
        var in = GoldenVectors.v2Input();
        assertThat(HashChain.compute(in, G, 2)).isNotEqualTo(HashChain.compute(in, "1".repeat(64), 2));
    }
}
