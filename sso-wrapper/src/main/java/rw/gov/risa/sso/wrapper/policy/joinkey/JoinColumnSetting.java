package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The join column in force for one client, plus who set it and when.
 *
 * @param clientId  the client's {@code clientId}
 * @param column    the column in force ({@code email} when nothing is recorded)
 * @param explicit  false when nothing (usable) is recorded and {@code column} is the default
 * @param changedBy provenance: who last set it, or {@code null}
 * @param changedAt provenance: when (ISO-8601 string as stored), or {@code null}
 * @param malformed the raw attribute value when it held something that is not a column, else
 *                  {@code null} (kept and reported rather than erased)
 */
public record JoinColumnSetting(String clientId, JoinColumn column, boolean explicit, String changedBy,
                                String changedAt, String malformed) {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    public JoinColumnSetting {
        column = column == null ? JoinKeys.DEFAULT_JOIN_COLUMN : column;
    }

    /** Convenience: a setting without provenance. */
    public JoinColumnSetting(String clientId, JoinColumn column, boolean explicit) {
        this(clientId, column, explicit, null, null, null);
    }

    /** The defaulted setting (email, {@code explicit=false}). */
    public static JoinColumnSetting unset(String clientId) {
        return new JoinColumnSetting(clientId, JoinKeys.DEFAULT_JOIN_COLUMN, false);
    }

    public ColumnSpec spec() {
        return JoinKeys.specFor(column);
    }

    /** Always {@code join_key}. */
    public String claim() {
        return JoinKeys.JOIN_KEY_CLAIM;
    }

    /**
     * The client attributes recording this setting. Every key is always present so a
     * read-modify-write overwrites stale provenance; {@code changed_at} is {@code at} formatted
     * like Python's {@code isoformat()} in UTC ({@code 2026-09-03T10:00:00+00:00}).
     */
    public Map<String, String> toAttributes(String actor, Instant at) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(JoinKeys.ATTR_JOIN_COLUMN, column.value());
        out.put(JoinKeys.ATTR_CHANGED_BY, actor == null ? "" : actor.strip());
        out.put(JoinKeys.ATTR_CHANGED_AT, isoformat(at == null ? Instant.now() : at));
        return out;
    }

    static String isoformat(Instant at) {
        ZonedDateTime t = at.atZone(ZoneOffset.UTC);
        String base = SECONDS.format(t);
        int micros = t.getNano() / 1000;
        if (micros != 0) {
            base += String.format(Locale.ROOT, ".%06d", micros);
        }
        return base + "+00:00";
    }

    /**
     * JSON-safe view: {@code client_id, join_column, claim, user_source, explicit, changed_by,
     * changed_at, malformed_value}.
     */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        out.put("join_column", column.value());
        out.put("claim", claim());
        out.put("user_source", spec().source());
        out.put("explicit", explicit);
        out.put("changed_by", changedBy);
        out.put("changed_at", changedAt);
        out.put("malformed_value", malformed);
        return out;
    }

    /** A new explicit setting with {@code column} and no provenance. */
    public JoinColumnSetting withColumn(JoinColumn newColumn) {
        return new JoinColumnSetting(clientId, newColumn, true);
    }

    /**
     * The setting a Keycloak client records. Tolerant: blank → default; unknown → default with
     * {@code malformed} set; case-insensitive.
     *
     * @param attributes client attributes (string- or list-valued), may be {@code null}
     */
    public static JoinColumnSetting parse(String clientId, Map<String, ?> attributes) {
        String cid = clean(clientId);
        Map<String, ?> attrs = attributes == null ? Map.of() : attributes;
        String raw = clean(attrs.get(JoinKeys.ATTR_JOIN_COLUMN));
        String changedBy = blankToNull(clean(attrs.get(JoinKeys.ATTR_CHANGED_BY)));
        String changedAt = blankToNull(clean(attrs.get(JoinKeys.ATTR_CHANGED_AT)));
        if (raw.isEmpty()) {
            return unset(cid);
        }
        return JoinColumn.fromValue(raw.toLowerCase(Locale.ROOT))
                .map(c -> new JoinColumnSetting(cid, c, true, changedBy, changedAt, null))
                .orElseGet(() -> new JoinColumnSetting(cid, JoinKeys.DEFAULT_JOIN_COLUMN, false, changedBy,
                        changedAt, raw));
    }

    static String clean(Object value) {
        if (value instanceof String s) {
            return s.strip();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "" : clean(list.get(0));
        }
        return "";
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
