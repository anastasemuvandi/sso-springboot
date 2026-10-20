package rw.gov.risa.sso.wrapper.policy.delegated;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.function.Consumer;

/** The reference tests' BASE_CONFIG, as a fresh mutable tree each call. */
final class Fixtures {

    static final String BASE_CONFIG = """
            {
              "version": 1,
              "guardrail": {
                "forbidden_realm_roles": ["admin", "super_admin", "manage-users"],
                "forbidden_client_roles": {"realm-management": ["*"]}
              },
              "assignments": {
                "default": ["local-admin", "local-hr"],
                "by_institution": {"mineduc": ["local-hr"]}
              },
              "templates": {
                "local-admin": {
                  "display_name": "Institution administrator",
                  "grant_role": "inst:{slug}:admin",
                  "capabilities": ["view-members", "manage-members", "manage-membership", "map-roles",
                                   "view-clients", "manage-clients"],
                  "members": {"group": "/{slug}", "include_subgroups": true},
                  "roles": {"allow": ["inst:{slug}:*"], "deny": ["inst:{slug}:admin"]},
                  "clients": {"allow": ["{slug}-*"], "deny": ["{slug}-internal"]},
                  "provision": {"roles": ["admin", "hr", "staff"]}
                },
                "local-hr": {
                  "display_name": "HR officer",
                  "grant_role": "inst:{slug}:hr",
                  "capabilities": ["view-members", "manage-members", "map-roles"],
                  "members": {"group": "/{slug}", "include_subgroups": false},
                  "roles": {"allow": ["inst:{slug}:staff"], "deny": []},
                  "clients": {"allow": [], "deny": []},
                  "provision": {"roles": ["hr", "staff"]}
                },
                "local-auditor": {
                  "display_name": "Auditor",
                  "grant_role": "inst:{slug}:auditor",
                  "capabilities": ["view-members", "view-clients"],
                  "members": {"group": "/{slug}", "include_subgroups": true},
                  "roles": {"allow": [], "deny": []},
                  "clients": {"allow": ["{slug}-*"], "deny": []},
                  "provision": {"roles": ["auditor"]}
                }
              }
            }
            """;

    /** The reference service tests' CONFIG. */
    static final String SERVICE_CONFIG = """
            {
              "version": 1,
              "guardrail": {
                "forbidden_realm_roles": ["admin", "super_admin", "manage-users"],
                "forbidden_client_roles": {"realm-management": ["*"]}
              },
              "assignments": {"default": ["local-admin", "local-hr"], "by_institution": {"mineduc": ["local-hr"]}},
              "templates": {
                "local-admin": {
                  "grant_role": "inst:{slug}:admin",
                  "capabilities": ["view-members", "manage-members", "map-roles"],
                  "members": {"group": "/{slug}"},
                  "roles": {"allow": ["inst:{slug}:*"], "deny": ["inst:{slug}:admin"]},
                  "clients": {"allow": ["{slug}-*"]},
                  "provision": {"roles": ["admin", "staff"]}
                },
                "local-hr": {
                  "grant_role": "inst:{slug}:hr",
                  "capabilities": ["view-members", "manage-members"],
                  "members": {"group": "/{slug}"},
                  "roles": {"allow": ["inst:{slug}:staff"]},
                  "clients": {"allow": []},
                  "provision": {"roles": ["hr", "staff"]}
                }
              }
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tree(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> base() {
        return tree(BASE_CONFIG);
    }

    static DelegatedPermissionRegistry registry() {
        return DelegatedPermissionsParser.fromMap(base(), "<test>");
    }

    static DelegatedPermissionRegistry serviceRegistry() {
        return DelegatedPermissionsParser.fromMap(tree(SERVICE_CONFIG), "<test>");
    }

    /** BASE_CONFIG with top-level keys replaced (values are JSON). */
    static DelegatedPermissionRegistry build(String key, String jsonValue) {
        Map<String, Object> raw = base();
        raw.put(key, parseValue(jsonValue));
        return DelegatedPermissionsParser.fromMap(raw, "<test>");
    }

    /** BASE_CONFIG with fields of `local-admin` replaced (values are JSON). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withTemplate(String field, String jsonValue) {
        Map<String, Object> raw = base();
        Map<String, Object> t = (Map<String, Object>) ((Map<String, Object>) raw.get("templates")).get("local-admin");
        t.put(field, parseValue(jsonValue));
        return raw;
    }

    static Map<String, Object> edit(Consumer<Map<String, Object>> change) {
        Map<String, Object> raw = base();
        change.accept(raw);
        return raw;
    }

    static Object parseValue(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
