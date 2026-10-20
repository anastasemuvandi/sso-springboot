package rw.gov.risa.sso.wrapper.service.institutionadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

class InstitutionAuditServiceTest {

    static InstitutionContext ctx(String slug) {
        var reg = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        return new InstitutionContext("a", "uid", slug, reg.templates().get("institution-auditor").render(slug),
                List.of(slug), false, List.of());
    }

    @Test
    void theInstitutionIsForcedAndForeignOrUntaggedEventsAreDropped() {
        AuditServiceClient reader = mock(AuditServiceClient.class);
        when(reader.listEventsForInstitution(eq("minagri"), any())).thenReturn(new ObjectMapper().valueToTree(Map.of("total", 99, "limit", 100, "offset", 0, "items", List.of(
                Map.of("id", 1, "institution", "minagri"),
                Map.of("id", 2, "details", Map.of("institution", "minagri")),
                Map.of("id", 3, "institution", "minisante"),
                Map.of("id", 4)))));
        Map<String, Object> page = new InstitutionAuditService(reader).events(ctx("minagri"),
                new InstitutionAuditService.Filters(null, null, null, null, null, null, 100, 0));
        assertThat(page.get("total")).isEqualTo(2);
        assertThat(page.get("scope_filtered_out")).isEqualTo(2);
        verify(reader).listEventsForInstitution(eq("minagri"), argThat(q -> q.eventType() == null && q.limit() == 100));
    }

    @Test
    void aNonPageAnswerIsAnEmptyPage() {
        assertThat(InstitutionAuditService.enforceInstitutionScope("garbage", "minagri"))
                .containsEntry("total", 0).containsEntry("items", List.of());
    }
}
