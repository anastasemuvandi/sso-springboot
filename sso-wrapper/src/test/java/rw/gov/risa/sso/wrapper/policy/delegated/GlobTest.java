package rw.gov.risa.sso.wrapper.policy.delegated;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Expected values generated with CPython 3.14 {@code fnmatch.fnmatchcase}. */
class GlobTest {

    @ParameterizedTest(name = "fnmatchcase({0}, {1}) == {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "a|[z-a]|false",
            "a|[!z-a]|true",
            "-|[!-z]|false",
            "z|[!-z]|false",
            "b|[!-z]|true",
            "[|[|true",
            "[a|[a|true",
            "a]|[]]|false",
            "]|[]]|true",
            "!|[!]|false",
            "[!]|[!]|true",
            "a|[!]|false",
            "!|[!!]|false",
            "-|[a-]|true",
            "b|[a-c]|true",
            "d|[a-c]|false",
            "\\|[\\]|true",
            "ab|a*|true",
            "x|?|true",
            "\"\"|*|true",
            "\"\"|?|false",
            "^|[^a]|true",
            "b|[^a]|false",
            "-|[!z-a-c]|false",
            "c|[!z-a-c]|false",
            "b|[!z-a-c]|true",
            "-|[z-a-c]|true",
            "b|[z-a-c]|false",
            "c|[z-a-c]|true",
            "inst:minagri:staff|inst:minagri:*|true",
            "inst:minagri|inst:minagri:*|false",
            "INST:minagri:x|inst:minagri:*|false",
            "minagri-kubaka-web|minagri-*|true",
            "a.b|a?b|true",
            "a+b|a+b|true",
            "a(b)|a(b)|true",
            "a$|a$|true",
            "aXb|a.b|false",
            "abc|a**c|true",
            "abc|*b*|true"})
    void matchesLikeCPython(String name, String pattern, boolean expected) {
        assertThat(Glob.matches(name, pattern)).isEqualTo(expected);
    }

    @Test
    void newlinesAreMatchedByStar() {
        assertThat(Glob.matches("a\nb", "a*b")).isTrue();
        assertThat(Glob.matches("a\nb", "a?b")).isTrue();
    }

    @Test
    void nullsNeverMatch() {
        assertThat(Glob.matches(null, "*")).isFalse();
        assertThat(Glob.matches("a", null)).isFalse();
        assertThat(Glob.matchesAny("a", null)).isFalse();
        assertThat(Glob.matchesAny("a", List.of())).isFalse();
        assertThat(Glob.matchesAny("a", List.of("b", "a"))).isTrue();
    }
}
