package rw.gov.risa.sso.wrapper.policy.registry;

import java.util.Collection;
import java.util.Map;

/** Python truthiness and repr helpers, so the ported validator reads like its source. */
final class PyLike {
    private PyLike() {
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** Python {@code repr()} for the scalar values that appear in validator messages. */
    static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String s) {
            return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(repr(o));
                first = false;
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    static String blankToNull(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
