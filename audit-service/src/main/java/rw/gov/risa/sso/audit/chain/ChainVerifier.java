package rw.gov.risa.sso.audit.chain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Streaming hash-chain verifier (reference {@code app/core/integrity.py}), pure Java.
 *
 * <p>Feed rows in chain order (created_at ascending). A row is counted as tampered when its
 * recomputed hash differs from the stored one, or when its {@code prev_hash} is not the
 * previous row's hash. Every row is checked (the count is the total, not "stop at first"), and
 * the first offender is reported, as in the reference.
 *
 * <p>Two refinements over the reference, both so the verifier does not cry wolf on legitimate
 * history:
 * <ul>
 *   <li><b>Anchor.</b> A windowed check ({@code from_date}) starts from the hash of the last row
 *       before the window instead of the genesis hash; the reference flagged the first row of
 *       every window.</li>
 *   <li><b>Retention gaps.</b> Rows removed by the retention purge leave a hash-only record
 *       ({@code audit_purged_links}, written by the DB trigger). A {@code prev_hash} that does not
 *       match is accepted only if an unbroken run of purged links leads from the expected hash
 *       to it. A row deleted any other way leaves no link, and is reported.</li>
 * </ul>
 */
public final class ChainVerifier {

    /** Answers "is there a run of purged rows leading from {@code fromHash} to {@code toHash}?". */
    @FunctionalInterface
    public interface PurgeBridge {
        boolean bridges(String fromHash, String toHash);

        PurgeBridge NONE = (a, b) -> false;
    }

    /** One stored row as the verifier sees it. */
    public record StoredEvent(String id, OffsetDateTime createdAt, String prevHash, String eventHash,
                              int hashVersion, HashChain.HashInput input) {
    }

    private final PurgeBridge bridge;
    private String expectedPrev;
    private long total;
    private long tampered;
    private String firstTamperedId;
    private OffsetDateTime firstTamperedAt;

    public ChainVerifier(String anchorHash, PurgeBridge bridge) {
        this.expectedPrev = anchorHash == null ? HashChain.GENESIS_HASH : anchorHash;
        this.bridge = bridge == null ? PurgeBridge.NONE : bridge;
    }

    public void accept(StoredEvent e) {
        total++;
        int version = e.hashVersion() <= 0 ? 1 : e.hashVersion();
        String recomputed = HashChain.compute(e.input(), e.prevHash(), version);
        boolean hashOk = recomputed.equals(e.eventHash());
        boolean linkOk = Objects.equals(e.prevHash(), expectedPrev)
                || (e.prevHash() != null && bridge.bridges(expectedPrev, e.prevHash()));
        if (!hashOk || !linkOk) {
            tampered++;
            if (firstTamperedId == null) {
                firstTamperedId = e.id();
                firstTamperedAt = e.createdAt();
            }
        }
        expectedPrev = e.eventHash();
    }

    /**
     * Whether the last verified row is the chain head recorded at write time (or reaches it
     * through purged rows). Detects deletion of the newest rows, which a prev-hash walk alone
     * cannot see.
     */
    public boolean tailMatchesHead(String headHash) {
        return Objects.equals(expectedPrev, headHash) || bridge.bridges(expectedPrev, headHash);
    }

    public long total() {
        return total;
    }

    public long tampered() {
        return tampered;
    }

    public String firstTamperedId() {
        return firstTamperedId;
    }

    public OffsetDateTime firstTamperedAt() {
        return firstTamperedAt;
    }
}
