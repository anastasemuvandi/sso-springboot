package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.List;
import java.util.Map;

/**
 * The parts of a Keycloak user the join-key rules read — adapt a {@code UserRepresentation} into
 * this. {@code attributes} is Keycloak's multi-valued map and may be {@code null} (a brief user
 * listing carries none).
 *
 * @param id         the user id
 * @param username   the username (reporting only)
 * @param email      the {@code email} user property, or {@code null}
 * @param attributes custom attributes ({@code national_id}, {@code phone}, …), or {@code null}
 */
public record JoinKeyUser(String id, String username, String email, Map<String, List<String>> attributes) {

    public static JoinKeyUser of(String id, String username, String email) {
        return new JoinKeyUser(id, username, email, null);
    }

    /** First value of an attribute, trimmed; "" when absent or empty. */
    public String attribute(String name) {
        if (attributes == null) {
            return "";
        }
        List<String> values = attributes.get(name);
        if (values == null || values.isEmpty()) {
            return "";
        }
        String first = values.get(0);
        return first == null ? "" : first.strip();
    }
}
