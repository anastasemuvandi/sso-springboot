package rw.gov.risa.sso.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PyJsonTest {

    @Test
    void dumpsMatchesPythonSortKeysEnsureAscii() {
        assertThat(PyJson.dumps(GoldenVectors.details())).isEqualTo(GoldenVectors.DUMPS);
    }

    @Test
    void emptyContainers() {
        assertThat(PyJson.dumps(Map.of())).isEqualTo("{}");
        assertThat(PyJson.dumps(Map.of("a", List.of()))).isEqualTo("{\"a\": []}");
    }

    @Test
    void keysSortByCodePointNotUtf16() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("\uD83D\uDE00", 1); // U+1F600, surrogates 0xD83D... sort before U+FF5E in UTF-16
        m.put("\uFF5E", 2);
        m.put("B", 3);
        m.put("a", 4);
        assertThat(PyJson.dumps(m)).isEqualTo("{\"B\": 3, \"a\": 4, \"\\uff5e\": 2, \"\\ud83d\\ude00\": 1}");
    }

    @Test
    void controlCharactersUseShortOrUnicodeEscapes() {
        assertThat(PyJson.dumps(Map.of("k", "\b\f\r\u0001 ~"))).isEqualTo("{\"k\": \"\\b\\f\\r\\u0001 ~\"}");
    }

    @Test
    void nonJsonValuesFallBackToStr() {
        assertThat(PyJson.dumps(Map.of("k", new StringBuilder("x")))).isEqualTo("{\"k\": \"x\"}");
    }

    /** Expected values printed by CPython {@code repr(float)} for the same bit patterns. */
    @ParameterizedTest
    @CsvSource({
        "0x1.0000000000000p+0, 1.0",
        "0x1.999999999999ap-4, 0.1",
        "0x1.4f8b588e368f1p-17, 1e-05",
        "0x1.a36e2eb1c432dp-14, 0.0001",
        "0x1.1c37937e08000p+53, 1e+16",
        "0x1.c6bf526340000p+49, 1000000000000000.0",
        "0x1.d6f34547df3b6p+26, 123456789.123",
        "0x1.1eb2d66005835p+997, 1.5e+300",
        "0x1.ac9a7b3b7302fp-996, 2.5e-300",
        "-0x1.91eb851eb851fp+1, -3.14",
        "0x1.5555555555555p-2, 0.3333333333333333",
        "0x0.0000000000001p-1022, 5e-324",
        "0x1.fffffffffffffp+1023, 1.7976931348623157e+308",
        "0x1.0000000000000p+53, 9007199254740992.0",
        "0x1.3333333333334p-2, 0.30000000000000004",
        "0x1.0f0cf064dd592p+73, 1e+22",
        "0x1.18b54f22aeb03p+50, 1234567890123456.8",
    })
    void floatReprMatchesPython(String hex, String expected) {
        assertThat(PyJson.floatRepr(Double.parseDouble(hex))).isEqualTo(expected);
    }

    /** ~3800 doubles (random bit patterns, random decimals, every power of ten) printed by CPython. */
    @Test
    void floatReprMatchesPythonCorpus() throws Exception {
        try (var in = PyJsonTest.class.getResourceAsStream("/py-float-repr.txt")) {
            String[] lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).split("\n");
            assertThat(lines.length).isGreaterThan(3000);
            for (String line : lines) {
                String[] p = line.split(" ");
                assertThat(PyJson.floatRepr(Double.parseDouble(p[0]))).as(line).isEqualTo(p[1]);
            }
        }
    }

    @Test
    void specialFloats() {
        assertThat(PyJson.floatRepr(Double.NaN)).isEqualTo("NaN");
        assertThat(PyJson.floatRepr(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(PyJson.floatRepr(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
        assertThat(PyJson.floatRepr(0.0)).isEqualTo("0.0");
        assertThat(PyJson.floatRepr(-0.0)).isEqualTo("-0.0");
    }
}
