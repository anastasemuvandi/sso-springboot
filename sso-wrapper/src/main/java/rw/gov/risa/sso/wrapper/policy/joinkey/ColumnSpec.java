package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything the system needs to know about one join column.
 *
 * @param column     the column
 * @param label      human label ("National ID", "Phone number", "Email address")
 * @param source     where the value lives on the Keycloak user ({@code national_id}, {@code phone},
 *                   or the {@code email} property)
 * @param isProperty true for a user <i>property</i> (email) rather than a custom attribute: decides
 *                   the mapper type and where the value is read from
 * @param shape      the shape a normalised value must match
 * @param shapeHint  human description of {@code shape}
 */
public record ColumnSpec(JoinColumn column, String label, String source, boolean isProperty, Pattern shape,
                         String shapeHint) {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()./]");

    /** The user's raw value for this column, trimmed; "" when absent. */
    public String read(JoinKeyUser user) {
        if (user == null) {
            return "";
        }
        if (isProperty) {
            return user.email() == null ? "" : user.email().strip();
        }
        return user.attribute(source);
    }

    /**
     * The comparable form — for validation and duplicate comparison only, never written back.
     * Email lowercases; NID and phone drop whitespace and {@code - ( ) . /}.
     */
    public String normalise(String value) {
        String raw = value == null ? "" : value.strip();
        if (raw.isEmpty()) {
            return "";
        }
        if (column == JoinColumn.EMAIL) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return SEPARATORS.matcher(raw).replaceAll("");
    }

    /**
     * Empty when the value is usable (or blank — presence is not checked here), else why not.
     */
    public Optional<String> validate(String value) {
        String normalised = normalise(value);
        if (normalised.isEmpty() || shape.matcher(normalised).matches()) {
            return Optional.empty();
        }
        return Optional.of(label + " '" + value + "' is not a usable " + column.value() + " value: expected "
                + shapeHint + ".");
    }

    /** The desired {@code sso-join-key} mapper emitting this column as {@code claim}. */
    public MapperSpec mapper(String claim) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("user.attribute", source);
        config.put("claim.name", claim);
        config.put("jsonType.label", "String");
        config.put("id.token.claim", "true");
        config.put("access.token.claim", "true");
        config.put("userinfo.token.claim", "true");
        config.put("introspection.token.claim", "true");
        config.put("multivalued", "false");
        config.put("aggregate.attrs", "false");
        return new MapperSpec(null, JoinKeys.JOIN_KEY_MAPPER_NAME, "openid-connect",
                isProperty ? "oidc-usermodel-property-mapper" : "oidc-usermodel-attribute-mapper", config);
    }

    /** {@link #mapper(String)} with the standard {@code join_key} claim. */
    public MapperSpec mapper() {
        return mapper(JoinKeys.JOIN_KEY_CLAIM);
    }

    /**
     * Field to search duplicates by: {@code email} ({@code GET /users?email=}) when
     * {@link #isProperty}, else an attribute name ({@code GET /users?q=<attr>:<value>}).
     */
    public String searchField() {
        return source;
    }
}
