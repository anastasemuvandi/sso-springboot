package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionKeycloak;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * SPEC §5.2 — a missing or unsafe delegated-permission config makes EVERY {@code /institution-admin/*}
 * route fail closed with 503 {@code delegation_unavailable}, end to end, before Keycloak is touched —
 * for an institution admin and a platform admin alike.
 */
@WrapperTest
@TestPropertySource(properties = "sso.delegated.permissions-config=/nonexistent/delegated/templates.json")
class DelegationFailClosedIsolationTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuditEmitter audit;
    @MockitoBean
    KeycloakAdminFactory keycloak;
    @MockitoBean
    InstitutionKeycloak kc;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "GET|/institution-admin/me|",
            "GET|/institution-admin/permissions|",
            "GET|/institution-admin/overview|",
            "GET|/institution-admin/roles|",
            "GET|/institution-admin/audit|",
            "GET|/institution-admin/users|",
            "POST|/institution-admin/users|{\"username\":\"xyz\",\"email\":\"x@y.rw\",\"firstName\":\"X\",\"lastName\":\"Y\"}",
            "GET|/institution-admin/users/u-a1|",
            "PATCH|/institution-admin/users/u-a1|{\"enabled\":false}",
            "POST|/institution-admin/users/u-a1/deactivate|",
            "POST|/institution-admin/users/u-a1/roles|{\"role\":\"inst:minagri:staff\"}",
            "DELETE|/institution-admin/users/u-a1/roles|{\"role\":\"inst:minagri:staff\"}",
            "GET|/institution-admin/apps|",
            "POST|/institution-admin/apps|{\"app\":\"portal\",\"redirect_uris\":[\"https://a\"]}",
            "GET|/institution-admin/apps/join-columns|",
            "GET|/institution-admin/apps/minagri-x/secret|",
            "GET|/institution-admin/apps/minagri-x/users|",
            "POST|/institution-admin/apps/minagri-x/access|",
            "DELETE|/institution-admin/apps/minagri-x/access|",
            "GET|/institution-admin/apps/minagri-x/join-column|",
            "PUT|/institution-admin/apps/minagri-x/join-column|{\"join_column\":\"nid\"}",
            "PUT|/institution-admin/apps/minagri-x/mfa|{\"required\":true}",
            "POST|/institution-admin/access/clients-for-users|{\"user_ids\":[\"u-a1\"]}",
            "POST|/institution-admin/access/revoke|{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-x\"]}"})
    void everyRouteIs503(String method, String path, String body) throws Exception {
        var req = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(req.with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("delegation_unavailable"));
        mvc.perform(request(HttpMethod.valueOf(method), path + "?institution=minagri")
                        .contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body)
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(kc);
    }
}
