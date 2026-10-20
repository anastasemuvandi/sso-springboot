package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Serialises a tree of {@code Map}/{@code List}/{@code String}/{@code Number}/{@code Boolean}/null
 * exactly as Python's {@code json.dumps(obj, indent=2)} does (default {@code ensure_ascii=True},
 * item separator {@code ","} at line ends, key separator {@code ": "}, {@code []}/{@code {}}
 * for empty containers, map iteration order preserved).
 */
public final class PyJson {

    private PyJson() {
    }

    public static String dumps(Object value, int indent) {
        StringBuilder b = new StringBuilder();
        write(b, value, indent, 0);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int indent, int level) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            string(b, s);
        } else if (v instanceof Boolean bool) {
            b.append(bool ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            b.append(v);
        } else if (v instanceof Number n) {
            throw new IllegalArgumentException("non-integer numbers are not supported: " + n);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                newline(b, indent, level + 1);
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent, level + 1);
                if (it.hasNext()) {
                    b.append(',');
                }
            }
            newline(b, indent, level);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                newline(b, indent, level + 1);
                write(b, l.get(i), indent, level + 1);
                if (i < l.size() - 1) {
                    b.append(',');
                }
            }
            newline(b, indent, level);
            b.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + v.getClass());
        }
    }

    private static void newline(StringBuilder b, int indent, int level) {
        b.append('\n');
        b.append(" ".repeat(indent * level));
    }

    /** {@code json.encoder.py_encode_basestring_ascii}. */
    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        // UTF-16 code units == Python's surrogate-pair output for astral chars.
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
