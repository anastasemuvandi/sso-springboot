package rw.gov.risa.sso.notification.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.notification.detector.AuditEvent;

class AuditClientParsingTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void acceptsPagedItemsEventsOrBareArray() throws Exception {
        assertThat(AuditClient.items(om.readTree("{\"total\":1,\"items\":[{\"a\":1}]}"))).hasSize(1);
        assertThat(AuditClient.items(om.readTree("{\"events\":[{},{}]}"))).hasSize(2);
        assertThat(AuditClient.items(om.readTree("[{},{},{}]"))).hasSize(3);
        assertThat(AuditClient.items(om.readTree("{}"))).isEmpty();
    }

    @Test
    void mapsAuditEventReadFields() throws Exception {
        JsonNode n = om.readTree("""
                {"id":"6f1c","event_type":"auth.login.failed","category":"authentication","user_id":"alice",
                 "ip_address":"10.0.0.1","app_id":null,"created_at":"2026-10-20T10:00:00.5+00:00"}""");
        AuditEvent e = AuditClient.toEvent(n);
        assertThat(e.id()).isEqualTo("6f1c");
        assertThat(e.isLoginFailure()).isTrue();
        assertThat(e.appId()).isNull();
        assertThat(e.createdAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00.5Z"));
    }

    @Test
    void missingIdGetsAStableSyntheticOneAndBadTimestampsAreDropped() throws Exception {
        JsonNode n = om.readTree("{\"event_type\":\"x\",\"created_at\":\"2026-10-20T10:00:00Z\"}");
        assertThat(AuditClient.toEvent(n).id()).startsWith("sha256:").isEqualTo(AuditClient.toEvent(n).id());
        assertThat(AuditClient.toEvent(om.readTree("{\"created_at\":\"nope\"}"))).isNull();
        assertThat(AuditClient.toEvent(om.readTree("{}"))).isNull();
    }
}
