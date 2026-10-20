package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The backfill worklist ({@code GET /institution-admin/apps/join-columns}) — summary over the
 * caller's in-scope apps. The service asserts {@code view-clients} (403) first.
 *
 * <p>{@code configurable} = owned by the caller's institution AND the caller may manage clients
 * (exactly the conditions the write path enforces). {@code outstanding} counts unconfigured rows
 * the caller can act on; {@code needs_platform_admin} the rest (e.g. cross-cutting apps);
 * {@code malformed} counts hand-edited nonsense values separately.
 */
public final class JoinColumnBacklog {

    private JoinColumnBacklog() {
    }

    /**
     * One in-scope app.
     *
     * @param owned       the caller's institution owns it (false for cross-cutting)
     * @param manageable  the caller's template allows managing this client
     */
    public record App(String clientId, String name, boolean owned, boolean crossCutting, boolean manageable,
                      JoinColumnSetting setting) {

        public boolean configurable() {
            return owned && manageable;
        }

        public boolean configured() {
            return setting.explicit();
        }

        /** {@code clientId, name, owned, cross_cutting, configurable, join_column (describe), configured}. */
        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clientId", clientId);
            out.put("name", name);
            out.put("owned", owned);
            out.put("cross_cutting", crossCutting);
            out.put("configurable", configurable());
            out.put("join_column", setting.describe());
            out.put("configured", configured());
            return out;
        }
    }

    /**
     * {@code institution, choices, total, configured, unconfigured, outstanding,
     * needs_platform_admin, malformed, items}.
     */
    public static Map<String, Object> summarise(String institution, List<App> apps) {
        int unconfigured = 0;
        int outstanding = 0;
        int malformed = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (App app : apps) {
            items.add(app.describe());
            if (!app.configured()) {
                unconfigured++;
                if (app.configurable()) {
                    outstanding++;
                }
            }
            if (app.setting().malformed() != null && !app.setting().malformed().isEmpty()) {
                malformed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", institution);
        out.put("choices", JoinKeys.choices());
        out.put("total", apps.size());
        out.put("configured", apps.size() - unconfigured);
        out.put("unconfigured", unconfigured);
        out.put("outstanding", outstanding);
        out.put("needs_platform_admin", unconfigured - outstanding);
        out.put("malformed", malformed);
        out.put("items", items);
        return out;
    }
}
