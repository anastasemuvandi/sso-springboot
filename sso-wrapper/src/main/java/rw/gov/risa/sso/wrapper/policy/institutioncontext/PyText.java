package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.Collection;
import java.util.stream.Collectors;

/** Python-style repr of strings and lists, so messages read as the reference's do. */
final class PyText {

    private PyText() {
    }

    static String repr(String s) {
        if (s == null) {
            return "None";
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static String list(Collection<String> items) {
        return items.stream().map(PyText::repr).collect(Collectors.joining(", ", "[", "]"));
    }
}
