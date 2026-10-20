package rw.gov.risa.sso.audit.workers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KeycloakUserSourceTest {

    @Test
    void readsCertExpiryAndEmployeeIdAttributes() {
        var u = KeycloakUserSource.toCertUser(Map.of("id", "kc-1", "email", "a@gov.rw",
                "attributes", Map.of("certExpiry", List.of("2026-12-31"), "employeeId", List.of("E1"))));
        assertThat(u).isEqualTo(new KeycloakUserSource.CertUser("kc-1", "E1", "a@gov.rw", "2026-12-31"));
    }

    @Test
    void usersWithoutTheAttributeAreSkipped() {
        assertThat(KeycloakUserSource.toCertUser(Map.of("id", "kc-2"))).isNull();
        assertThat(KeycloakUserSource.toCertUser(Map.of("id", "kc-3", "attributes", Map.of("certExpiry", List.of()))))
                .isNull();
    }
}
