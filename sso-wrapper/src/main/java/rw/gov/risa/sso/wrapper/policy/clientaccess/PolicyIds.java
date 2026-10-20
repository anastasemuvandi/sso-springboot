package rw.gov.risa.sso.wrapper.policy.clientaccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Ids out of a policy's JSON-encoded {@code config.users} / {@code config.groups}. Unparseable
 * or wrongly-shaped config yields an empty list: one malformed policy must never make every
 * other policy on the client unreadable (or fail the gate).
 */
public final class PolicyIds {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PolicyIds() {
    }

    /** Non-empty string items of a JSON array. */
    public static List<String> userIds(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array(raw)) {
            if (item.isTextual() && !item.asText().isEmpty()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    /** The truthy {@code id} of each object item of a JSON array. */
    public static List<String> groupIds(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array(raw)) {
            if (item.isObject()) {
                JsonNode id = item.get("id");
                if (id != null && id.isTextual() && !id.asText().isEmpty()) {
                    out.add(id.asText());
                }
            }
        }
        return out;
    }

    public static String encodeUsers(List<String> userIds) {
        ArrayNode arr = JSON.createArrayNode();
        userIds.forEach(arr::add);
        return arr.toString();
    }

    public static String encodeGroups(List<String> groupIds) {
        ArrayNode arr = JSON.createArrayNode();
        for (String g : groupIds) {
            ObjectNode o = arr.addObject();
            o.put("id", g);
            o.put("extendChildren", false);
        }
        return arr.toString();
    }

    private static Iterable<JsonNode> array(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(raw);
            return node != null && node.isArray() ? node : List.of();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
