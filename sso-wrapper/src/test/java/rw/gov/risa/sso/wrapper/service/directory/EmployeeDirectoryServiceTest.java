package rw.gov.risa.sso.wrapper.service.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.DirectoryScope;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;

class EmployeeDirectoryServiceTest {

    @SuppressWarnings("unchecked")
    ObjectProvider<IdentityStore> none = mock(ObjectProvider.class);
    InstitutionContextService ctx = mock(InstitutionContextService.class);

    @Test
    void institutionTermIsFirstAndNoFilterRemovesIt() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> c = EmployeeDirectoryService.conditions(
                new EmployeeFilters("minagri", "x%_", "active", "unlinked", true), true, p);
        assertThat(c.get(0)).isEqualTo("i.short_code = :institution");
        assertThat(p.getValue("institution")).isEqualTo("minagri");
        assertThat(p.getValue("q")).isEqualTo("%x\\%\\_%");
        assertThat(c).anyMatch(s -> s.contains("keycloak_user_id IS NULL"));
    }

    @Test
    void platformScopeHasNoInstitutionTermAndSummaryIgnoresAccount() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> c = EmployeeDirectoryService.conditions(new EmployeeFilters(null, null, null, "linked", false),
                false, p);
        assertThat(c).isEmpty();
    }

    @Test
    void noMirrorIs503ButOnlyAfterValidation() {
        EmployeeDirectoryService s = new EmployeeDirectoryService(none, ctx);
        DirectoryScope scope = new DirectoryScope("a", "u", null, List.of(), true);
        assertThatThrownBy(() -> s.list(scope, null, null, null, false, null, false, 50, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.error()).isEqualTo("identity_db_not_configured");
                });
        assertThatThrownBy(() -> s.list(scope, null, null, "everyone", false, null, false, 50, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(422));
        assertThatThrownBy(() -> s.list(scope, null, null, null, false, "national_id", false, 50, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(422));
        assertThatThrownBy(() -> s.detail(scope, "EMP1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void scopeComesFromTheInstitutionContextService() {
        EmployeeDirectoryService s = new EmployeeDirectoryService(none, ctx);
        when(ctx.resolveDirectoryScope(any(), any())).thenThrow(ApiException.forbidden("not_institution_admin", "no"));
        assertThatThrownBy(() -> s.scope(null, null)).isInstanceOf(ApiException.class);
        verify(none, never()).getIfAvailable();
    }
}
