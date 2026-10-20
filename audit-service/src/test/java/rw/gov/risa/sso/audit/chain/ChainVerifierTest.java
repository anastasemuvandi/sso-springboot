package rw.gov.risa.sso.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ChainVerifierTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** Builds a valid in-memory chain, row i written at T0 + i seconds. */
    private static List<ChainVerifier.StoredEvent> chain(int n, int version) {
        List<ChainVerifier.StoredEvent> out = new ArrayList<>();
        String prev = HashChain.GENESIS_HASH;
        for (int i = 0; i < n; i++) {
            var in = new HashChain.HashInput("auth.login.success", "u" + i, T0.plusSeconds(i), Map.of("seq", i),
                    "actor" + i, "minagri", "t" + i, "app", "10.0.0." + i, "INFO");
            String h = HashChain.compute(in, prev, version);
            out.add(new ChainVerifier.StoredEvent("id-" + i, in.createdAt(), prev, h, version, in));
            prev = h;
        }
        return out;
    }

    private static ChainVerifier verify(List<ChainVerifier.StoredEvent> rows, String anchor,
                                        ChainVerifier.PurgeBridge bridge) {
        ChainVerifier v = new ChainVerifier(anchor, bridge);
        rows.forEach(v::accept);
        return v;
    }

    @Test
    void validChainVerifies() {
        var rows = chain(5, 2);
        var v = verify(rows, null, null);
        assertThat(v.tampered()).isZero();
        assertThat(v.total()).isEqualTo(5);
        assertThat(v.tailMatchesHead(rows.get(4).eventHash())).isTrue();
    }

    @Test
    void emptyChainMatchesGenesisHead() {
        assertThat(verify(List.of(), null, null).tailMatchesHead(HashChain.GENESIS_HASH)).isTrue();
    }

    @Test
    void editedFieldIsDetected() {
        var rows = chain(4, 2);
        var r = rows.get(2);
        var in = r.input();
        var edited = new HashChain.HashInput(in.eventType(), in.userId(), in.createdAt(), in.details(), "attacker",
                in.institution(), in.target(), in.appId(), in.ipAddress(), in.severity());
        rows.set(2, new ChainVerifier.StoredEvent(r.id(), r.createdAt(), r.prevHash(), r.eventHash(), 2, edited));
        var v = verify(rows, null, null);
        assertThat(v.tampered()).isEqualTo(1);
        assertThat(v.firstTamperedId()).isEqualTo("id-2");
        assertThat(v.firstTamperedAt()).isEqualTo(r.createdAt());
    }

    @Test
    void editedDetailsAreDetected() {
        var rows = chain(3, 2);
        var r = rows.get(0);
        var in = r.input();
        var edited = new HashChain.HashInput(in.eventType(), in.userId(), in.createdAt(), Map.of("seq", 99),
                in.performedBy(), in.institution(), in.target(), in.appId(), in.ipAddress(), in.severity());
        rows.set(0, new ChainVerifier.StoredEvent(r.id(), r.createdAt(), r.prevHash(), r.eventHash(), 2, edited));
        assertThat(verify(rows, null, null).firstTamperedId()).isEqualTo("id-0");
    }

    @Test
    void replacedHashIsDetectedOnTheRowAndItsSuccessor() {
        var rows = chain(3, 2);
        var r = rows.get(1);
        rows.set(1, new ChainVerifier.StoredEvent(r.id(), r.createdAt(), r.prevHash(), "a".repeat(64), 2, r.input()));
        var v = verify(rows, null, null);
        assertThat(v.tampered()).isEqualTo(2);
        assertThat(v.firstTamperedId()).isEqualTo("id-1");
    }

    @Test
    void deletedMiddleRowIsDetected() {
        var rows = chain(4, 2);
        rows.remove(1);
        var v = verify(rows, null, null);
        assertThat(v.tampered()).isEqualTo(1);
        assertThat(v.firstTamperedId()).isEqualTo("id-2");
    }

    @Test
    void deletedTailIsDetectedOnlyByTheHeadCheck() {
        var rows = chain(4, 2);
        String head = rows.get(3).eventHash();
        rows.remove(3);
        var v = verify(rows, null, null);
        assertThat(v.tampered()).isZero();
        assertThat(v.tailMatchesHead(head)).isFalse();
    }

    @Test
    void mixedV1AndV2ChainVerifies() {
        var v1 = chain(2, 1);
        String prev = v1.get(1).eventHash();
        var in = new HashChain.HashInput("auth.logout", "x", T0.plusSeconds(9), Map.of(), "x", null, null, null, null,
                "INFO");
        var row = new ChainVerifier.StoredEvent("id-v2", in.createdAt(), prev, HashChain.compute(in, prev, 2), 2, in);
        List<ChainVerifier.StoredEvent> rows = new ArrayList<>(v1);
        rows.add(row);
        assertThat(verify(rows, null, null).tampered()).isZero();
    }

    @Test
    void windowStartsFromAnchorNotGenesis() {
        var rows = chain(5, 2);
        var window = rows.subList(2, 5);
        assertThat(verify(window, null, null).tampered()).isEqualTo(1);
        assertThat(verify(window, rows.get(1).eventHash(), null).tampered()).isZero();
    }

    @Test
    void purgedRunIsBridgedOnlyThroughRecordedLinks() {
        var rows = chain(6, 2);
        Map<String, String> links = new HashMap<>(); // prev_hash -> event_hash of purged rows
        links.put(rows.get(1).prevHash(), rows.get(1).eventHash());
        links.put(rows.get(2).prevHash(), rows.get(2).eventHash());
        ChainVerifier.PurgeBridge bridge = (from, to) -> {
            String cur = from;
            for (int i = 0; i < 100; i++) {
                cur = links.get(cur);
                if (cur == null) {
                    return false;
                }
                if (cur.equals(to)) {
                    return true;
                }
            }
            return false;
        };
        List<ChainVerifier.StoredEvent> kept = new ArrayList<>(rows);
        kept.remove(2);
        kept.remove(1);
        assertThat(verify(kept, null, bridge).tampered()).isZero();

        // Row 4 deleted without a purge link: its successor (row 5) is reported.
        kept.remove(2);
        assertThat(kept).extracting(ChainVerifier.StoredEvent::id).containsExactly("id-0", "id-3", "id-5");
        var v = verify(kept, null, bridge);
        assertThat(v.tampered()).isEqualTo(1);
        assertThat(v.firstTamperedId()).isEqualTo("id-5");
    }
}
