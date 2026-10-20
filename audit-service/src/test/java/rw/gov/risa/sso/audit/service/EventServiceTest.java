package rw.gov.risa.sso.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EventServiceTest {

    @Test
    void detailStringTakesTheFirstNonBlankStringStripped() {
        Map<String, Object> d = new HashMap<>();
        d.put("target_user", "  ");
        d.put("target", " bob ");
        d.put("institution", 7);
        assertThat(EventService.detailString(d, "target_user", "target")).isEqualTo("bob");
        assertThat(EventService.detailString(d, "institution")).isNull();
        assertThat(EventService.detailString(null, "x")).isNull();
    }
}
