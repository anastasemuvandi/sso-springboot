package rw.gov.risa.sso.wrapper.api.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * Spec §5.16: every declared platform route is reachable — enumerate every {@code @RequestMapping} of
 * the five platform controllers and assert each admin-tier role (super_admin first) gets past the gate
 * (anything but 401/403), while an institution admin is refused (403) on every one of them.
 */
class PlatformRouteReachabilityTest extends PlatformApiTestBase {

    static final Set<String> PACKAGES = Set.of(
            "rw.gov.risa.sso.wrapper.api.client", "rw.gov.risa.sso.wrapper.api.users",
            "rw.gov.risa.sso.wrapper.api.agency", "rw.gov.risa.sso.wrapper.api.roles",
            "rw.gov.risa.sso.wrapper.api.authorization");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    record Route(RequestMethod method, String pattern) {
        String path() {
            return pattern.replaceAll("\\{[^}]+}", "x1");
        }
    }

    List<Route> routes() {
        List<Route> out = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!PACKAGES.contains(handler.getBeanType().getPackageName())) {
                return;
            }
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    out.add(new Route(m, pattern));
                }
            }
        });
        return out;
    }

    MockHttpServletRequestBuilder request(Route r) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(r.method().name()), r.path());
        if (r.method() != RequestMethod.GET) {
            b.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return b;
    }

    @Test
    void everyReferencePlatformRouteIsDeclared() {
        Set<String> declared = new TreeSet<>();
        routes().forEach(r -> declared.add(r.method() + " " + r.pattern()));
        // The reference contract's 74 operations for /client, /users, /agency, /roles, /authorization.
        assertThat(declared).hasSize(74);
        assertThat(declared).contains("GET /users/", "POST /agency/", "GET /agency/{group_id}/subgroups",
                "PUT /client/clients/{client_id}/scope", "PUT /client/clients/{client_id}/join-column",
                "PUT /client/clients/{client_id}/mfa", "GET /roles/client-id/{client_id}/uuid",
                "POST /authorization/users/{user_id}/client-access/{client_id}",
                "DELETE /users/{user_id}/sessions/{session_id}", "DELETE /roles/users/{user_id}/client-roles/{client_id}");
    }

    @Test
    void everyAdminTierRoleReachesEveryRoute() throws Exception {
        List<Route> routes = routes();
        assertThat(routes).isNotEmpty();
        List<String> refused = new ArrayList<>();
        for (RequestPostProcessor caller : List.of(TestCallers.superAdmin(), TestCallers.admin(),
                TestCallers.systemAdmin())) {
            for (Route r : routes) {
                int status = mvc.perform(request(r).with(caller)).andReturn().getResponse().getStatus();
                // The services are mocks that never throw, so 404/405 can only mean "no such route".
                if (status == 401 || status == 403 || status == 404 || status == 405) {
                    refused.add(r.method() + " " + r.pattern() + " -> " + status);
                }
            }
        }
        assertThat(refused).as("admin-tier callers refused on").isEmpty();
    }

    @Test
    void institutionAdminIsRefusedOnEveryRoute() throws Exception {
        List<String> passed = new ArrayList<>();
        for (Route r : routes()) {
            int status = mvc.perform(request(r).with(TestCallers.institutionAdmin("minagri", "admin")))
                    .andReturn().getResponse().getStatus();
            if (status != 403) {
                passed.add(r.method() + " " + r.pattern() + " -> " + status);
            }
        }
        assertThat(passed).as("institution admin was not refused on").isEmpty();
    }

    @Test
    void anonymousIs401OnEveryRoute() throws Exception {
        List<String> passed = new ArrayList<>();
        for (Route r : routes()) {
            int status = mvc.perform(request(r)).andReturn().getResponse().getStatus();
            if (status != 401) {
                passed.add(r.method() + " " + r.pattern() + " -> " + status);
            }
        }
        assertThat(passed).isEmpty();
    }
}
