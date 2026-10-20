package rw.gov.risa.sso.audit.chain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Byte-exact port of Python's {@code json.dumps(value, sort_keys=True, default=str)} with the
 * default arguments ({@code ensure_ascii=True}, separators {@code ", "} and {@code ": "}).
 *
 * <p>This is the {@code details} part of the hash input (see {@link HashChain}). It must match
 * the reference implementation exactly, or rows written by the Python service would stop
 * verifying the moment the Java service took over (and vice versa). The rules ported:
 * <ul>
 *   <li>object keys sorted by Unicode code point, recursively;</li>
 *   <li>{@code ", "} between items, {@code ": "} between key and value;</li>
 *   <li>every character outside printable ASCII ({@code ' '..'~'}) escaped as {@code \\uXXXX}
 *       (lower-case hex, UTF-16 code units, so astral characters become surrogate pairs);
 *       {@code \"}, {@code \\}, {@code \n}, {@code \r}, {@code \t}, {@code \b}, {@code \f}
 *       use their short escapes; {@code /} is not escaped;</li>
 *   <li>floats in Python {@code repr} form ({@code 1.0}, {@code 1e-05}, {@code 1e+16},
 *       {@code NaN}, {@code Infinity}); integers in plain decimal;</li>
 *   <li>{@code true}/{@code false}/{@code null}.</li>
 * </ul>
 * Anything that is not a JSON type falls back to {@code String.valueOf} rendered as a JSON
 * string, mirroring {@code default=str}.
 */
public final class PyJson {

    private PyJson() {
    }

    public static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double d) {
            sb.append(floatRepr(d));
        } else if (v instanceof Float f) {
            sb.append(floatRepr(f.doubleValue()));
        } else if (v instanceof BigDecimal bd) {
            // Python parses a JSON fraction into a float (double); do the same.
            sb.append(floatRepr(bd.doubleValue()));
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger) {
            sb.append(v.toString());
        } else if (v instanceof Map<?, ?> m) {
            writeMap(sb, m);
        } else if (v instanceof Collection<?> c) {
            writeList(sb, c);
        } else if (v instanceof Object[] arr) {
            writeList(sb, List.of(arr));
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            entries.add(Map.entry(String.valueOf(e.getKey()), e.getValue() == null ? NULL : e.getValue()));
        }
        entries.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : entries) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(": ");
            write(sb, e.getValue() == NULL ? null : e.getValue());
        }
        sb.append('}');
    }

    /** Map.entry rejects null values; this sentinel stands in for JSON null while sorting. */
    private static final Object NULL = new Object();

    private static void writeList(StringBuilder sb, Collection<?> c) {
        sb.append('[');
        boolean first = true;
        for (Object o : c) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            write(sb, o);
        }
        sb.append(']');
    }

    /** Python compares str by code point; Java's compareTo compares UTF-16 units. */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    static void writeString(StringBuilder sb, String s) {
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
                    if (c >= ' ' && c <= '~') {
                        sb.append(c);
                    } else {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Python {@code float.__repr__}: the shortest decimal string that round-trips (closest to the
     * exact value when several qualify), laid out with Python's rule —
     * scientific notation when the decimal exponent is {@code < -4} or {@code >= 16}.
     */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0.0) {
            return (1.0 / d < 0) ? "-0.0" : "0.0";
        }
        BigDecimal bd = shortest(Math.abs(d));
        String digits = bd.unscaledValue().toString();
        int decpt = digits.length() - bd.scale(); // value = 0.<digits> * 10^decpt
        StringBuilder out = new StringBuilder();
        if (d < 0) {
            out.append('-');
        }
        if (decpt <= -4 || decpt > 16) {
            out.append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits, 1, digits.length());
            }
            int exp = decpt - 1;
            out.append('e').append(exp < 0 ? '-' : '+');
            String e = Integer.toString(Math.abs(exp));
            if (e.length() < 2) {
                out.append('0');
            }
            out.append(e);
        } else if (decpt <= 0) {
            out.append("0.");
            out.append("0".repeat(-decpt));
            out.append(digits);
        } else if (decpt >= digits.length()) {
            out.append(digits);
            out.append("0".repeat(decpt - digits.length()));
            out.append(".0");
        } else {
            out.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return out.toString();
    }

    /**
     * Fewest significant digits that parse back to {@code d}. Rounding the exact binary value to p
     * digits gives the closest p-digit decimal, so the first p that round-trips is Python's choice.
     * ({@code Double.toString} is not used: it insists on two digits in scientific form, e.g.
     * {@code 4.9E-324} where Python prints {@code 5e-324}.)
     */
    private static BigDecimal shortest(double d) {
        BigDecimal exact = new BigDecimal(d);
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN));
            if (r.doubleValue() == d) {
                return r.stripTrailingZeros();
            }
        }
        return exact.round(new java.math.MathContext(17, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros();
    }
}
