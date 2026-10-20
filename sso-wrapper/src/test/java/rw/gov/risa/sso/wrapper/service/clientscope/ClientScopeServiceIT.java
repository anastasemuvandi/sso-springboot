package rw.gov.risa.sso.wrapper.service.clientscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

@Tag("it")
class ClientScopeServiceIT {

    static ItServices s;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        s = new ItServices();
    }

    @Test
    void narrowingAScopeIs409ThenConfirmWithdrawsThatInstitutionOnly() {
        String client = KeycloakIT.unique("portal");
        KeycloakIT.createClient(client);
        s.scopes.writeScope(client, s.scopes.requested(client, true, List.of("minagri", "minisante"), null));
        assertThat(s.scopes.getScope(client).crossCutting()).isTrue();

        var sa = s.access.serviceAccount();
        String agri = KeycloakIT.createUser(KeycloakIT.unique("sc-agri"), "minagri");
        String sante = KeycloakIT.createUser(KeycloakIT.unique("sc-sante"), "minisante");
        s.access.admitUsers(sa, client, List.of(agri, sante));
        assertThat(s.scopes.scopeState(client).access()).hasSize(2);

        var narrower = s.scopes.requested(client, true, List.of("minisante"), null);
        assertThatThrownBy(() -> s.scopes.changeScope(client, narrower, false, "platform.super", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).error()).isEqualTo("confirm_required");
                    assertThat(((ApiException) e).details()).containsKey("access_at_risk");
                });
        assertThat(s.scopes.getScope(client).institutions()).containsExactly("minagri", "minisante");

        var change = s.scopes.changeScope(client, narrower, true, "platform.super", null);
        assertThat(change.removedInstitutions()).containsExactly("minagri");
        assertThat(change.accessWithdrawn()).hasSize(1);
        assertThat(s.access.assignment(sa, client).userIds()).containsExactly(sante);
    }

    @Test
    void invalidScopeIs400AndPrefixInfersOwner() {
        assertThatThrownBy(() -> s.scopes.requested("x", true, List.of(), null)).isInstanceOf(ApiException.class);
        assertThat(s.scopes.getScope("minagri-kubaka-web").owner()).isEqualTo("minagri");
        assertThat(s.scopes.getScope("minagri-kubaka-web").inferred()).isTrue();
    }
}
