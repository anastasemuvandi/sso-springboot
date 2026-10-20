package rw.gov.risa.sso.audit.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The SHA-256 hash chain, ported byte-for-byte from the reference {@code app/core/logger.py}.
 *
 * <h2>Canonical form</h2>
 * The digest is {@code sha256(utf8(join("|", fields)))} as lower-case hex, where a null string
 * field is the empty string and:
 * <pre>
 * v1: prev_hash | event_type | user_id | created_at | details
 * v2: prev_hash | event_type | user_id | created_at | details
 *       | performed_by | institution | target | app_id | ip_address | severity
 * </pre>
 * <ul>
 *   <li>{@code created_at} is Python {@code datetime.isoformat()} of the UTC instant:
 *       {@code YYYY-MM-DDTHH:MM:SS[.ffffff]+00:00} — the fraction is exactly six digits and is
 *       omitted entirely when the microsecond is zero (see {@link #pyIsoFormat}). Timestamps are
 *       therefore truncated to microseconds before hashing <em>and</em> storing, matching
 *       Postgres {@code timestamptz} precision.</li>
 *   <li>{@code details} is {@code json.dumps(details, sort_keys=True, default=str)} (see
 *       {@link PyJson}); a null map hashes as {@code {}}.</li>
 * </ul>
 * v1 omits the actor ({@code performed_by}), app, ip and severity, so those are not
 * tamper-evident on v1 rows; that is why v2 exists. Rows carry the version they were written
 * with, and the verifier uses the same dispatch as the writer, so they can never drift.
 */
public final class HashChain {

    public static final String GENESIS_HASH = "0".repeat(64);

    /** Hash formula written for new events. */
    public static final int CURRENT_HASH_VERSION = 2;

    private HashChain() {
    }

    /** The fields that feed the hash. */
    public record HashInput(
            String eventType,
            String userId,
            OffsetDateTime createdAt,
            Map<String, ?> details,
            String performedBy,
            String institution,
            String target,
            String appId,
            String ipAddress,
            String severity) {
    }

    /** Dispatch on the row's version; versions {@code <= 1} use v1 (as the reference does). */
    public static String compute(HashInput e, String prevHash, int version) {
        String content = canonical(e, prevHash, version);
        return sha256Hex(content);
    }

    /** The exact string that is hashed. Public for documentation and tests. */
    public static String canonical(HashInput e, String prevHash, int version) {
        String details = PyJson.dumps(e.details() == null ? Map.of() : e.details());
        List<String> parts;
        if (version <= 1) {
            parts = List.of(
                    nz(prevHash), nz(e.eventType()), nz(e.userId()), pyIsoFormat(e.createdAt()), details);
        } else {
            parts = List.of(
                    nz(prevHash), nz(e.eventType()), nz(e.userId()), pyIsoFormat(e.createdAt()), details,
                    nz(e.performedBy()), nz(e.institution()), nz(e.target()), nz(e.appId()),
                    nz(e.ipAddress()), nz(e.severity()));
        }
        return String.join("|", parts);
    }

    /** Python {@code datetime.isoformat()} for an aware UTC datetime. */
    public static String pyIsoFormat(OffsetDateTime t) {
        OffsetDateTime u = t.withOffsetSameInstant(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        int year = u.getYear();
        sb.append(String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d",
                year, u.getMonthValue(), u.getDayOfMonth(), u.getHour(), u.getMinute(), u.getSecond()));
        int micros = u.getNano() / 1000;
        if (micros != 0) {
            sb.append(String.format(Locale.ROOT, ".%06d", micros));
        }
        sb.append("+00:00");
        return sb.toString();
    }

    /** Postgres keeps microseconds; anything finer would hash differently after a round trip. */
    public static OffsetDateTime toMicros(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
