package rw.gov.risa.sso.audit.chain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventCategoriesTest {

    @ParameterizedTest
    @CsvSource({
        "auth.login.success, AUTHENTICATION",
        "authz.access.denied, AUTHORIZATION",
        "admin.access_review.due, ADMIN",
        "lifecycle.offboard, LIFECYCLE",
        "session.refreshed, SESSION",
        "cert.expiry_warning, CERTIFICATE",
        "provision.user, UNKNOWN",
        "ippis.sync.attributes, UNKNOWN",
        "auth, UNKNOWN",
    })
    void prefixDecidesCategory(String type, String category) {
        assertThat(EventCategories.infer(type)).isEqualTo(category);
    }
}
