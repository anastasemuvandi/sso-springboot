package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AlertTextTest {

    @Test
    void humanizeIsPythonTitleCase() {
        assertThat(AlertText.humanize("brute_force")).isEqualTo("Brute Force");
        assertThat(AlertText.humanize("impossible_travel")).isEqualTo("Impossible Travel");
        assertThat(AlertText.humanize("MULTIPLE_sessions")).isEqualTo("Multiple Sessions");
    }

    @Test
    void titleAndSubject() {
        assertThat(AlertText.title("CRITICAL", "brute_force")).isEqualTo("[CRITICAL] SSO Alert — Brute Force");
        assertThat(AlertText.emailSubject("WARNING", "unusual_hours"))
                .isEqualTo("[WARNING] SSO Security Alert — Unusual Hours");
    }

    @Test
    void messageListsSubjectThenDetails() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("count", 5);
        d.put("ips", List.of("1.1.1.1"));
        assertThat(AlertText.message("brute_force", "alice", "1.1.1.1", null, d))
                .isEqualTo("User: alice; IP: 1.1.1.1; count: 5; ips: [1.1.1.1]");
        assertThat(AlertText.message("brute_force", null, null, null, Map.of())).isEqualTo("brute_force");
    }
}
