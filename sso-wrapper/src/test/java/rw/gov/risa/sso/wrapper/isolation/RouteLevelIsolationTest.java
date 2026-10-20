package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * SPEC §7.1 — the same isolation through every HTTP route of the slice: naming another institution is
 * refused (403, never honoured), a caller with no grant is refused, an anonymous one is 401 — and nothing
 * reaches Keycloak's write paths.
 */
class RouteLevelIsolationTest extends InstitutionAdminMvcSupport {

    static Stream<Arguments> routes() {
        String user = "{\"username\":\"xyz\",\"email\":\"x@y.rw\",\"firstName\":\"X\",\"lastName\":\"Y\"}";
        String role = "{\"role\":\"inst:minagri:staff\"}";
        String ids = "{\"user_ids\":[\"u-a1\"]}";
        return Stream.of(
                Arguments.of("GET", "/institution-admin/me", null),
                Arguments.of("GET", "/institution-admin/permissions", null),
                Arguments.of("GET", "/institution-admin/overview", null),
                Arguments.of("GET", "/institution-admin/roles", null),
                Arguments.of("GET", "/institution-admin/audit", null),
                Arguments.of("GET", "/institution-admin/users", null),
                Arguments.of("POST", "/institution-admin/users", user),
                Arguments.of("GET", "/institution-admin/users/u-a1", null),
                Arguments.of("PATCH", "/institution-admin/users/u-a1", "{\"enabled\":false}"),
                Arguments.of("POST", "/institution-admin/users/u-a1/deactivate", null),
                Arguments.of("POST", "/institution-admin/users/u-a1/roles", role),
                Arguments.of("DELETE", "/institution-admin/users/u-a1/roles", role),
                Arguments.of("GET", "/institution-admin/apps", null),
                Arguments.of("POST", "/institution-admin/apps", "{\"app\":\"portal\",\"redirect_uris\":[\"https://a\"]}"),
                Arguments.of("GET", "/institution-admin/apps/join-columns", null),
                Arguments.of("GET", "/institution-admin/apps/minagri-kubaka-web/secret", null),
                Arguments.of("GET", "/institution-admin/apps/minagri-kubaka-web/users", null),
                Arguments.of("POST", "/institution-admin/apps/minagri-kubaka-web/access", null),
                Arguments.of("DELETE", "/institution-admin/apps/minagri-kubaka-web/access", null),
                Arguments.of("GET", "/institution-admin/apps/minagri-kubaka-web/join-column", null),
                Arguments.of("PUT", "/institution-admin/apps/minagri-kubaka-web/join-column", "{\"join_column\":\"nid\"}"),
                Arguments.of("PUT", "/institution-admin/apps/minagri-kubaka-web/mfa", "{\"required\":true}"),
                Arguments.of("POST", "/institution-admin/access/clients-for-users", ids),
                Arguments.of("POST", "/institution-admin/access/revoke",
                        "{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"minagri-kubaka-web\"]}"));
    }

    private static MockHttpServletRequestBuilder req(String method, String path, String body) {
        MockHttpServletRequestBuilder b = request(HttpMethod.valueOf(method), path);
        return body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void nothingWritten() {
        verify(kc, never()).createUser(any(), any());
        verify(kc, never()).updateUser(any(), anyString(), any());
        verify(kc, never()).addRealmRoles(any(), anyString(), any());
        verify(kc, never()).removeRealmRoles(any(), anyString(), any());
        verify(kc, never()).createClient(any(), any());
        verify(policyStore, never()).apply(any(), anyString(), anyList());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    void namingAnotherInstitutionIs403(String method, String path, String body) throws Exception {
        mvc.perform(req(method, path + "?institution=" + B, body).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("institution_not_administered"));
        nothingWritten();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    void aCallerWithNoGrantIs403(String method, String path, String body) throws Exception {
        mvc.perform(req(method, path, body).with(TestCallers.plainUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_institution_admin"));
        nothingWritten();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    void anonymousIs401(String method, String path, String body) throws Exception {
        mvc.perform(req(method, path, body)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    void bsAdminCannotActOnAsObjects(String method, String path, String body) throws Exception {
        if (path.endsWith("/me") || path.endsWith("/permissions") || path.endsWith("/overview")
                || path.endsWith("/roles") && method.equals("GET") || path.endsWith("/audit")
                || path.endsWith("/users") && !path.contains("/apps/") || path.endsWith("/apps")
                || path.endsWith("/join-columns")) {
            return; // institution-level reads/creates: B's admin gets B's own data (covered elsewhere)
        }
        String own = body == null ? null : body.replace("inst:minagri:staff", "inst:minisante:staff");
        mvc.perform(req(method, path, own)
                        .with(TestCallers.institutionAdmin(B, "admin")))
                .andExpect(status().isForbidden());
        nothingWritten();
    }
}
