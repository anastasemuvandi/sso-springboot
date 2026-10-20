package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * A Keycloak account as the resolution needs it.
 *
 * @param institution the account's {@code institution} attribute normalised (trimmed, lowercased),
 *                    or {@code null} when it records none — "unknown", never "somebody else's"
 */
public record Account(String id, String username, String institution) {

    /** Build from a user id, username and its (multi-valued) attributes. */
    public static Account of(String id, String username, Map<String, ? extends Collection<String>> attributes) {
        return new Account(id, username, institutionOf(attributes));
    }

    /** The {@code institution} attribute, first value, trimmed and lowercased; {@code null} when blank/absent. */
    public static String institutionOf(Map<String, ? extends Collection<String>> attributes) {
        if (attributes == null) {
            return null;
        }
        Collection<String> values = attributes.get("institution");
        if (values == null || values.isEmpty()) {
            return null;
        }
        String v = values.iterator().next();
        if (v == null) {
            return null;
        }
        String norm = v.strip().toLowerCase(Locale.ROOT);
        return norm.isEmpty() ? null : norm;
    }
}
