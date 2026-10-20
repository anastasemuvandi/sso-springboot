package rw.gov.risa.sso.wrapper.service.joinkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

@Tag("it")
class JoinKeyServiceIT {

    static ItServices s;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        s = new ItServices();
    }

    private static ProtocolMapperRepresentation mapper(String clientId) {
        return KeycloakIT.realm().clients().get(KeycloakIT.clientUuid(clientId)).getProtocolMappers().getMappers()
                .stream().filter(m -> "sso-join-key".equals(m.getName())).findFirst().orElse(null);
    }

    @Test
    void settingTheColumnReconcilesTheOneMapper() {
        String client = KeycloakIT.unique("minagri-jk");
        KeycloakIT.createClient(client);
        assertThat(s.joinKeys.getSetting(client).explicit()).isFalse();

        var first = s.joinKeys.setJoinColumn(client, JoinColumn.EMAIL, "tester", false);
        assertThat(first.mapper()).isEqualTo("created");
        assertThat(mapper(client).getProtocolMapper()).isEqualTo("oidc-usermodel-property-mapper");
        assertThat(mapper(client).getConfig()).containsEntry("claim.name", "join_key");

        // Re-key with nobody assigned and no session: no confirm needed; same mapper object updated.
        var second = s.joinKeys.setJoinColumn(client, JoinColumn.NID, "tester", false);
        assertThat(second.mapper()).isEqualTo("updated");
        assertThat(mapper(client).getProtocolMapper()).isEqualTo("oidc-usermodel-attribute-mapper");
        assertThat(mapper(client).getConfig()).containsEntry("user.attribute", "national_id");
        assertThat(s.joinKeys.getSetting(client).column()).isEqualTo(JoinColumn.NID);
        assertThat(s.joinKeys.getSetting(client).changedBy()).isEqualTo("tester");

        assertThat(s.joinKeys.setJoinColumn(client, JoinColumn.NID, "tester", false).mapper()).isEqualTo("unchanged");
    }

    @Test
    void rekeyingAClientWithAssignedUsersNeedsConfirm() {
        String client = KeycloakIT.unique("minagri-rekey");
        KeycloakIT.createClient(client);
        s.joinKeys.setJoinColumn(client, JoinColumn.EMAIL, "tester", false);
        String uid = KeycloakIT.createUser(KeycloakIT.unique("jk-assigned"), "minagri");
        s.access.admitUser(s.access.serviceAccount(), client, uid);

        assertThatThrownBy(() -> s.joinKeys.setJoinColumn(client, JoinColumn.PHONE, "tester", false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("confirm_required"));
        var done = s.joinKeys.setJoinColumn(client, JoinColumn.PHONE, "tester", true);
        assertThat(done.assignedUsers()).isEqualTo(1);
        assertThat(done.readiness().rows()).hasSize(1); // no phone on record
    }

    @Test
    void loginGateMissingInvalidNotUniqueAndUnsetSkipped() {
        String client = KeycloakIT.unique("minisante-gate");
        KeycloakIT.createClient(client);
        String missing = KeycloakIT.unique("jk-missing");
        KeycloakIT.createUser(missing, "minisante");
        // Unset client: not gated.
        assertThat(s.joinKeys.checkLogin(missing, client)).isEmpty();

        s.joinKeys.setJoinColumn(client, JoinColumn.NID, "tester", false);
        assertThatThrownBy(() -> s.joinKeys.enforceLogin(missing, client, null, "password"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("join_key_missing"));

        String bad = KeycloakIT.unique("jk-bad");
        KeycloakIT.createUser(bad, bad + "@it.gov.rw", "minisante", Map.of("national_id", List.of("12")), List.of());
        assertThat(s.joinKeys.checkLogin(bad, client).orElseThrow().resolution().error().code())
                .isEqualTo("join_key_invalid");

        String nid = String.format("%016d", (System.nanoTime() & Long.MAX_VALUE) % 10_000_000_000_000_000L);
        String a = KeycloakIT.unique("jk-dup-a");
        String b = KeycloakIT.unique("jk-dup-b");
        KeycloakIT.createUser(a, a + "@it.gov.rw", "minisante", Map.of("national_id", List.of(nid)), List.of());
        assertThat(s.joinKeys.checkLogin(a, client).orElseThrow().ok()).isTrue();
        KeycloakIT.createUser(b, b + "@it.gov.rw", "minisante", Map.of("national_id", List.of(nid)), List.of());
        assertThat(s.joinKeys.checkLogin(a, client).orElseThrow().resolution().error().code())
                .isEqualTo("join_key_not_unique");

        // Identifier write gate: 400 malformed, 409 held by someone else, own value fine.
        assertThatThrownBy(() -> s.joinKeys.validateIdentifierWrite(false, null,
                Map.of("national_id", List.of("abc")), null))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("identifier_malformed"));
        final String held = nid;
        assertThatThrownBy(() -> s.joinKeys.validateIdentifierWrite(false, null,
                Map.of("national_id", List.of(held)), null))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("identifier_taken"));
        s.joinKeys.validateIdentifierWrite(true, KeycloakIT.unique("fresh") + "@it.gov.rw", Map.of(), null);
    }

    @Test
    void exemptClientsAreNeverGated() {
        assertThat(s.joinKeys.checkLogin("minagri.staff", "sso-admin-ui")).isEmpty();
    }
}
