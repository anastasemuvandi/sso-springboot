package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * An exact port of Python's {@code fnmatch.fnmatchcase}: case-sensitive shell-style
 * globbing with {@code *}, {@code ?}, {@code [seq]} and {@code [!seq]}.
 *
 * <p>The delegated templates are matched with these semantics in the reference
 * implementation, so the allow/deny patterns in {@code templates.json} must mean the
 * same thing here — including the odd corners (an unclosed {@code [} is a literal, a
 * reversed range is empty, {@code []]} is a set containing {@code ]}).
 */
public final class Glob {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Glob() {
    }

    /** {@code fnmatchcase(name, pattern)}. */
    public static boolean matches(String name, String pattern) {
        if (name == null || pattern == null) {
            return false;
        }
        return CACHE.computeIfAbsent(pattern, p -> Pattern.compile(translate(p), Pattern.DOTALL))
                .matcher(name)
                .matches();
    }

    /** True if {@code name} matches any of {@code patterns}. */
    public static boolean matchesAny(String name, Collection<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (matches(name, p)) {
                return true;
            }
        }
        return false;
    }

    /** Translate a glob into a Java regex (full-match semantics), following CPython's {@code fnmatch.translate}. */
    static String translate(String pat) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = pat.length();
        while (i < n) {
            char c = pat.charAt(i++);
            if (c == '*') {
                // collapse runs of '*'
                while (i < n && pat.charAt(i) == '*') {
                    i++;
                }
                out.append(".*");
            } else if (c == '?') {
                out.append('.');
            } else if (c == '[') {
                int j = i;
                if (j < n && pat.charAt(j) == '!') {
                    j++;
                }
                if (j < n && pat.charAt(j) == ']') {
                    j++;
                }
                while (j < n && pat.charAt(j) != ']') {
                    j++;
                }
                if (j >= n) {
                    out.append(lit('['));
                    continue;
                }
                String stuff = pat.substring(i, j);
                List<String> chunks = new ArrayList<>();
                if (stuff.indexOf('-') < 0) {
                    chunks.add(stuff);
                } else {
                    int start = i;
                    int k = pat.charAt(i) == '!' ? i + 2 : i + 1;
                    while (true) {
                        k = indexOf(pat, '-', k, j);
                        if (k < 0) {
                            break;
                        }
                        chunks.add(pat.substring(start, k));
                        start = k + 1;
                        k = k + 3;
                    }
                    String chunk = pat.substring(start, j);
                    if (!chunk.isEmpty()) {
                        chunks.add(chunk);
                    } else {
                        int last = chunks.size() - 1;
                        chunks.set(last, chunks.get(last) + "-");
                    }
                    // Remove empty ranges -- invalid in a regex.
                    for (int m = chunks.size() - 1; m >= 1; m--) {
                        String prev = chunks.get(m - 1);
                        String cur = chunks.get(m);
                        if (prev.charAt(prev.length() - 1) > cur.charAt(0)) {
                            chunks.set(m - 1, prev.substring(0, prev.length() - 1) + cur.substring(1));
                            chunks.remove(m);
                        }
                    }
                }
                i = j + 1;
                String joined = String.join("-", chunks);
                if (joined.isEmpty()) {
                    out.append("(?!)");
                } else if (joined.equals("!")) {
                    out.append('.');
                } else {
                    out.append('[');
                    boolean first = true;
                    for (int m = 0; m < chunks.size(); m++) {
                        String ch = chunks.get(m);
                        if (m > 0) {
                            out.append('-');
                        }
                        for (int q = 0; q < ch.length(); q++) {
                            char x = ch.charAt(q);
                            if (first && q == 0 && m == 0 && x == '!') {
                                out.append('^');
                            } else {
                                out.append(lit(x));
                            }
                        }
                        first = false;
                    }
                    out.append(']');
                }
            } else {
                out.append(lit(c));
            }
        }
        return out.toString();
    }

    private static int indexOf(String s, char c, int from, int to) {
        for (int k = Math.max(from, 0); k < to; k++) {
            if (s.charAt(k) == c) {
                return k;
            }
        }
        return -1;
    }

    private static String lit(char c) {
        return String.format("\\u%04x", (int) c);
    }
}
