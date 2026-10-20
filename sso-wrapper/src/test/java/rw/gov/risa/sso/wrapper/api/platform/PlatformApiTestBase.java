package rw.gov.risa.sso.wrapper.api.platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService;
import rw.gov.risa.sso.wrapper.service.platform.AuthorizationAdminService;
import rw.gov.risa.sso.wrapper.service.platform.ClientAdminService;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * One shared Spring context for every platform-admin controller test: the five platform services are
 * mocked (their Keycloak work is covered by the service tests and the {@code @Tag("it")} suite).
 */
@WrapperTest
abstract class PlatformApiTestBase {

    @Autowired protected MockMvc mvc;
    @MockitoBean protected AuditEmitter audit;
    @MockitoBean protected KeycloakAdminFactory keycloak;
    @MockitoBean protected ClientAdminService clients;
    @MockitoBean protected UserAdminService users;
    @MockitoBean protected AgencyAdminService agencies;
    @MockitoBean protected RoleAdminService roles;
    @MockitoBean protected AuthorizationAdminService authz;
}
