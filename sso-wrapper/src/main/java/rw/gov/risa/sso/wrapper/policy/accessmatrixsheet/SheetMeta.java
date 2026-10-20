package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The signed frame of an export: institution, applications, export id, when and by whom. The HMAC
 * covers this, deliberately not the cells.
 *
 * @param exportedAt ISO-8601, UTC
 */
public record SheetMeta(String institution, List<String> clients, String exportId, String exportedAt,
                        String exportedBy) {

    private static final DateTimeFormatter PY_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    public SheetMeta {
        clients = List.copyOf(clients);
    }

    /**
     * The signed payload, byte-compatible with the reference's
     * {@code json.dumps(payload, sort_keys=True, separators=(",", ":"))} (ASCII-escaped), so a file
     * exported by either implementation verifies in the other.
     */
    public String canonicalPayload() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"clients\":[");
        for (int i = 0; i < clients.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Json.string(sb, clients.get(i));
        }
        sb.append("],\"export_id\":");
        Json.string(sb, exportId);
        sb.append(",\"exported_at\":");
        Json.string(sb, exportedAt);
        sb.append(",\"exported_by\":");
        Json.string(sb, exportedBy);
        sb.append(",\"format\":");
        Json.string(sb, AccessMatrixSheet.FORMAT);
        sb.append(",\"institution\":");
        Json.string(sb, institution);
        sb.append(",\"version\":").append(AccessMatrixSheet.VERSION).append('}');
        return sb.toString();
    }

    /** {@code exported_at} in the reference's format: {@code 2026-10-20T08:00:00.000000+00:00}. */
    public static String isoUtc(Instant at) {
        return PY_ISO.format(at.atOffset(ZoneOffset.UTC));
    }

    /** Minimal JSON string encoding matching Python's {@code ensure_ascii=True}. */
    static final class Json {
        private Json() {
        }

        static void string(StringBuilder sb, String s) {
            if (s == null) {
                sb.append("null");
                return;
            }
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20 || c > 0x7f) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        /** Python {@code json.dumps(list)} with default separators: {@code ["a", "b"]}. */
        static String list(List<String> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                string(sb, items.get(i));
            }
            return sb.append(']').toString();
        }
    }
}
