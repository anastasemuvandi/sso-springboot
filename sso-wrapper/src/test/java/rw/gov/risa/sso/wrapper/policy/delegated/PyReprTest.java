package rw.gov.risa.sso.wrapper.policy.delegated;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PyReprTest {

    @Test
    void stringsUsePythonQuoting() {
        assertThat(PyRepr.str("minagri")).isEqualTo("'minagri'");
        assertThat(PyRepr.str("it's")).isEqualTo("\"it's\"");
        assertThat(PyRepr.str("it's \"x\"")).isEqualTo("'it\\'s \"x\"'");
        assertThat(PyRepr.str("a\\b\n\t\r\u0001")).isEqualTo("'a\\\\b\\n\\t\\r\\x01'");
        assertThat(PyRepr.str("café")).isEqualTo("'café'");
        assertThat(PyRepr.str(null)).isEqualTo("None");
    }

    @Test
    void listsUsePythonFormatting() {
        assertThat(PyRepr.list(List.of())).isEqualTo("[]");
        assertThat(PyRepr.list(List.of("inst:minagri:admin", "inst:minagri:hr")))
                .isEqualTo("['inst:minagri:admin', 'inst:minagri:hr']");
    }
}
