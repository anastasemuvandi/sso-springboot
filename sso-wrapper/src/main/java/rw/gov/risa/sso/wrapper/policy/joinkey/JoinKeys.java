package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Constants, the column table and the strict write-path parser (port of {@code join_key.py}).
 */
public final class JoinKeys {

    public static final String ATTR_JOIN_COLUMN = "sso.client.join_column";
    public static final String ATTR_CHANGED_BY = "sso.client.join_column.changed_by";
    public static final String ATTR_CHANGED_AT = "sso.client.join_column.changed_at";

    /** Every client attribute this module owns; the generic client update refuses these keys. */
    public static final List<String> JOIN_COLUMN_ATTRIBUTES = List.of(ATTR_JOIN_COLUMN, ATTR_CHANGED_BY,
            ATTR_CHANGED_AT);

    /** The claim every client's join key arrives in, whatever the column. */
    public static final String JOIN_KEY_CLAIM = "join_key";

    /** The one protocol mapper the wrapper owns on each client (matched by name). */
    public static final String JOIN_KEY_MAPPER_NAME = "sso-join-key";

    /** Keycloak user attribute holding the NIDA national id (sourced from IPPIS). */
    public static final String ATTR_NATIONAL_ID = "national_id";
    /** Keycloak user attribute holding the phone. */
    public static final String ATTR_PHONE = "phone";

    /** Rwanda's NIDA identifier length. */
    public static final int NID_DIGITS = 16;

    /** Unset means email (with {@code explicit=false}). */
    public static final JoinColumn DEFAULT_JOIN_COLUMN = JoinColumn.EMAIL;

    private static final Map<JoinColumn, ColumnSpec> SPECS;

    static {
        Map<JoinColumn, ColumnSpec> specs = new EnumMap<>(JoinColumn.class);
        specs.put(JoinColumn.NID, new ColumnSpec(JoinColumn.NID, "National ID", ATTR_NATIONAL_ID, false,
                Pattern.compile("^\\d{" + NID_DIGITS + "}$"), "exactly " + NID_DIGITS + " digits"));
        specs.put(JoinColumn.PHONE, new ColumnSpec(JoinColumn.PHONE, "Phone number", ATTR_PHONE, false,
                Pattern.compile("^\\+?\\d{9,15}$"), "9–15 digits, optionally with a leading '+'"));
        specs.put(JoinColumn.EMAIL, new ColumnSpec(JoinColumn.EMAIL, "Email address", "email", true,
                Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$"), "an address of the form name@domain.tld"));
        SPECS = java.util.Collections.unmodifiableMap(specs);
    }

    private JoinKeys() {
    }

    public static ColumnSpec specFor(JoinColumn column) {
        return SPECS.get(column);
    }

    /** All specs in column order (nid, phone, email). */
    public static List<ColumnSpec> specs() {
        return List.copyOf(SPECS.values());
    }

    /**
     * The console pick-list: {@code value, label, source, shape, default} per column.
     */
    public static List<Map<String, Object>> choices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ColumnSpec spec : SPECS.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", spec.column().value());
            row.put("label", spec.label());
            row.put("source", spec.source());
            row.put("shape", spec.shapeHint());
            row.put("default", spec.column() == DEFAULT_JOIN_COLUMN);
            out.add(row);
        }
        return out;
    }

    /**
     * Strict parse of a requested column (case/space-insensitive). A typo never falls back to the
     * default.
     *
     * @throws JoinColumnException on blank/unknown
     */
    public static JoinColumn requested(String value) {
        String raw = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            throw new JoinColumnException("join_column is required. Choose one of ['nid', 'phone', 'email'] — it "
                    + "decides what the 'join_key' claim carries for this client.");
        }
        return JoinColumn.fromValue(raw).orElseThrow(() -> new JoinColumnException(
                "join_column '" + value + "' is not one of ['nid', 'phone', 'email']."));
    }

    /** Join-column attribute keys offered in a generic client-update body, sorted (non-empty ⇒ 400). */
    public static List<String> joinColumnAttributesIn(java.util.Collection<String> attributeKeys) {
        java.util.TreeSet<String> offered = new java.util.TreeSet<>();
        if (attributeKeys != null) {
            for (String key : attributeKeys) {
                if (JOIN_COLUMN_ATTRIBUTES.contains(key)) {
                    offered.add(key);
                }
            }
        }
        return List.copyOf(offered);
    }
}
