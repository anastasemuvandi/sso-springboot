package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.Collection;
import java.util.StringJoiner;

/**
 * Python {@code repr()} of strings and string lists.
 *
 * <p>The reference implementation's error messages and (more importantly) the
 * generated FGAP plan's descriptions embed {@code repr(str)} / {@code repr(list)}
 * — e.g. {@code "Holders of ['inst:minagri:admin'] in 'minagri'"}. Reproducing it
 * exactly keeps messages and the committed plan byte-identical.
 */
public final class PyRepr {

    private PyRepr() {
    }

    /** {@code repr(s)} — single quotes unless the string contains {@code '} and no {@code "}. */
    public static String str(String s) {
        if (s == null) {
            return "None";
        }
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder b = new StringBuilder(s.length() + 2).append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == quote || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                b.append(String.format("\\x%02x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append(quote).toString();
    }

    /** {@code repr(list_of_str)} — {@code ['a', 'b']}, {@code []} when empty. */
    public static String list(Collection<String> values) {
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (String v : values) {
            j.add(str(v));
        }
        return j.toString();
    }
}
